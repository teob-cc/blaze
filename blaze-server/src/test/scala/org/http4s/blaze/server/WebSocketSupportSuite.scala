/*
 * Copyright 2014 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s
package blaze
package server

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import cats.effect.unsafe.IORuntimeConfig
import cats.effect.unsafe.Scheduler
import fs2.Pipe
import munit.FunSuite
import org.http4s.internal.threads._
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame

import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets.US_ASCII
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.Try

// Not a CatsEffectSuite on purpose: if the single compute thread gets blocked, the runtime
// cannot fire any timeouts, so all waiting is done on the test thread with socket timeouts.
class WebSocketSupportSuite extends FunSuite {

  // The server's default execution context is the runtime's compute pool, so with a single
  // compute thread any blocking wait on the Dispatcher from a blaze callback deadlocks.
  private val singleThreadRuntime = FunFixture[IORuntime](
    setup = { _ =>
      val compute =
        Executors.newSingleThreadExecutor(threadFactory(i => s"ws-suite-compute-$i", daemon = true))
      val blocking = newBlockingPool("ws-suite-blocking")
      val (scheduler, shutdownScheduler) = Scheduler.createDefaultScheduler()
      IORuntime(
        ExecutionContext.fromExecutor(compute),
        ExecutionContext.fromExecutor(blocking),
        scheduler,
        () => {
          compute.shutdownNow()
          blocking.shutdownNow()
          shutdownScheduler()
        },
        IORuntimeConfig(),
      )
    },
    teardown = _.shutdown(),
  )

  // The default runtime: a work-stealing pool. A blocking wait on one of its compute threads
  // does not deadlock, the worker hands its queue to another thread and becomes a blocker
  // thread instead, which the pool counts.
  private val workStealingRuntime = FunFixture[IORuntime](
    setup = _ => IORuntime.builder().build(),
    teardown = _.shutdown(),
  )

  private val echo: Pipe[IO, WebSocketFrame, WebSocketFrame] =
    _.collect { case WebSocketFrame.Text(text, _) => WebSocketFrame.Text(text) }

  singleThreadRuntime.test("upgrade to a websocket on a single-threaded compute pool") {
    implicit runtime =>
      withServer(_.build(echo)) { port =>
        assertEquals(echoOverRawSocket(port, List("hello")), List("hello"))
      }
  }

  workStealingRuntime.test("upgrade to a websocket without blocking a compute worker") {
    implicit runtime =>
      val pool = runtime.metrics.workStealingThreadPool.getOrElse(fail("not a work-stealing pool"))
      def blockingCount: Long = pool.workerThreads.map(_.blockingCount()).sum

      withServer(_.build(echo)) { port =>
        val before = blockingCount
        assertEquals(echoOverRawSocket(port, List("hello")), List("hello"))
        assertEquals(
          blockingCount - before,
          0L,
          "a compute worker was turned into a blocker thread during the websocket upgrade",
        )
      }
  }

  workStealingRuntime.test("frames sent right after the 101 response arrive in order") {
    implicit runtime =>
      val messages = List("one", "two", "three")
      withServer(_.build(echo)) { port =>
        assertEquals(echoOverRawSocket(port, messages), messages)
      }
  }

  workStealingRuntime.test("onClose runs when the client disconnects right after the upgrade") {
    implicit runtime =>
      val closed = IO.deferred[Unit].unsafeRunSync()
      withServer(_.withOnClose(closed.complete(()).void).build(echo)) { port =>
        val socket = new Socket("127.0.0.1", port)
        try {
          socket.setSoTimeout(5000)
          handshake(socket.getOutputStream, socket.getInputStream, port)
        } finally socket.close()

        assert(
          closed.get.unsafeRunTimed(5.seconds).isDefined,
          "onClose was not called within 5 seconds of the client disconnecting",
        )
      }
  }

  /** Runs `body` against a server whose only route upgrades to the websocket `ws` builds. A
    * failure in `body` is reported over a failure to release the server.
    */
  private def withServer[A](
      ws: WebSocketBuilder2[IO] => IO[Response[IO]]
  )(body: Int => A)(implicit runtime: IORuntime): A = {
    val (server, release) = BlazeServerBuilder[IO]
      .bindAny()
      .withHttpWebSocketApp(wsb => HttpApp[IO](_ => ws(wsb)))
      .resource
      .allocated
      .unsafeRunSync()
    val result = Try(body(server.address.getPort))
    val released = release.unsafeRunTimed(5.seconds).isDefined
    val a = result.get
    assert(released, "the server did not release within 5 seconds")
    a
  }

  /** Upgrades the connection, sends all `messages` as text frames in a single write and returns
    * the text frames received back.
    */
  private def echoOverRawSocket(port: Int, messages: List[String]): List[String] = {
    val socket = new Socket("127.0.0.1", port)
    try {
      socket.setSoTimeout(5000)
      val out = socket.getOutputStream
      val in = socket.getInputStream
      handshake(out, in, port)

      out.write(messages.toArray.flatMap(textFrame))
      out.flush()

      try messages.map(_ => readTextFrame(in))
      catch {
        case _: SocketTimeoutException =>
          fail(
            "No websocket frame received: the pipeline was never switched to the websocket stage"
          )
      }
    } finally socket.close()
  }

  private def handshake(out: OutputStream, in: InputStream, port: Int): Unit = {
    out.write(
      ("GET / HTTP/1.1\r\n" +
        s"Host: 127.0.0.1:$port\r\n" +
        "Upgrade: websocket\r\n" +
        "Connection: Upgrade\r\n" +
        "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
        "Sec-WebSocket-Version: 13\r\n" +
        "\r\n").getBytes(US_ASCII)
    )
    out.flush()
    val responseHead = readResponseHead(in)
    assert(responseHead.startsWith("HTTP/1.1 101"), responseHead)
    // The accept value for the example key of RFC 6455 section 1.3
    assert(
      responseHead.contains("Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n"),
      responseHead,
    )
  }

  // Client frames must be masked (RFC 6455 section 5.3)
  private def textFrame(message: String): Array[Byte] = {
    val payload = message.getBytes(UTF_8)
    val mask = Array[Byte](1, 2, 3, 4)
    val masked = payload.zipWithIndex.map { case (b, i) => (b ^ mask(i % 4)).toByte }
    Array(0x81.toByte, (0x80 | payload.length).toByte) ++ mask ++ masked
  }

  private def readTextFrame(in: InputStream): String = {
    assertEquals(in.read(), 0x81, "expected a final text frame")
    val length = in.read() & 0x7f
    new String(Array.fill(length)(in.read().toByte), UTF_8)
  }

  private def readResponseHead(in: InputStream): String = {
    val sb = new StringBuilder
    while (!sb.toString.endsWith("\r\n\r\n")) {
      val b = in.read()
      if (b == -1) fail(s"Connection closed before the end of the response head: $sb")
      sb.append(b.toChar)
    }
    sb.result()
  }
}
