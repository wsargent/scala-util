package io.wasted.util.http

import io.wasted.util.Logger

import io.netty.bootstrap._
import io.netty.buffer._
import io.netty.channel._
import io.netty.channel.socket._
import io.netty.channel.socket.nio._
import io.netty.handler.codec.http._
import io.netty.handler.ssl.SslHandler
import io.netty.handler.timeout._

import java.security.KeyStore
import java.net.InetSocketAddress
import java.io.{ File, FileInputStream }
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.{ SSLEngine, SSLContext }

/**
 * Netty HTTP Client Object to create HTTP Request Objects.
 */
object HttpClient {
  /**
   * Creates a HTTP Client which will call the given method with the returned HttpResponse.
   *
   * @param timeout Connect timeout in seconds
   * @param engine Optional SSLEngine
   */
  def apply(timeout: Int = 5, engine: Option[SSLEngine] = None): HttpClient[Object] = {
    val doneF = (x: Option[io.netty.handler.codec.http.HttpResponse]) => {}
    this.apply(new HttpClientResponseAdapter(doneF), timeout, engine)
  }

  /**
   * Creates a HTTP Client which will call the given method with the returned HttpResponse.
   *
   * @param doneF Function which will handle the result
   * @param timeout Connect timeout in seconds
   * @param engine Optional SSLEngine
   */
  def apply(doneF: (Option[HttpResponse]) => Unit, timeout: Int, engine: Option[SSLEngine]): HttpClient[Object] =
    this.apply(new HttpClientResponseAdapter(doneF), timeout, engine)

  /**
   * Creates a HTTP Client which implements the given Netty HandlerAdapter.
   *
   * @param handler Implementation of ChannelInboundMessageHandlerAdapter
   * @param timeout Connect timeout in seconds
   * @param engine Optional SSLEngine
   */
  def apply[T <: Object](handler: ChannelInboundMessageHandlerAdapter[T], timeout: Int, engine: Option[SSLEngine]): HttpClient[T] =
    new HttpClient(handler, timeout, engine)

  /* Default Client SSLContext. */
  lazy val defaultClientSSLContext: SSLContext = {
    val ks = KeyStore.getInstance("JKS")
    val ts = KeyStore.getInstance("JKS")
    val passphrase = "defaultClientStorePass".toCharArray

    val keyStoreFile = File.createTempFile("keyStoreFile", ".jks")
    keyStoreFile.deleteOnExit
    ks.load(new FileInputStream(keyStoreFile), passphrase)

    val kmf = KeyManagerFactory.getInstance("SunX509")
    kmf.init(ks, passphrase)

    val sslCtx = SSLContext.getInstance("TLS")
    sslCtx.init(kmf.getKeyManagers(), null, null)
    sslCtx
  }

  /**
   * Get a SSLEngine for clients for given host and port.
   *
   * @param host Hostname
   * @param port Port
   */
  def getSSLClientEngine(host: String, port: Int): SSLEngine = {
    val ce = defaultClientSSLContext.createSSLEngine(host, port)
    ce.setUseClientMode(true)
    ce
  }
}

/**
 * Netty Response Adapter which is used for the doneF-Approach in HttpClient Object.
 *
 * @param doneF Function which will handle the result
 */
@ChannelHandler.Sharable
class HttpClientResponseAdapter(doneF: (Option[HttpResponse]) => Unit) extends ChannelInboundMessageHandlerAdapter[Object] with Logger {
  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
    ExceptionHandler(ctx, cause)
    doneF(None)
    ctx.channel.close
  }

  override def messageReceived(ctx: ChannelHandlerContext, msg: Object) {
    msg match {
      case a: HttpResponse => doneF(Some(a))
      case _ => doneF(None)
    }
  }
}

/**
 * Netty Http Client class which will do all of the Setup needed to make simple HTTP Requests.
 *
 * @param handler Inbound Message Handler Implementation
 * @param timeout Connect timeout in seconds
 */
class HttpClient[T <: Object](handler: ChannelInboundMessageHandlerAdapter[T], timeout: Int = 5, engine: Option[SSLEngine] = None) extends Logger {

  private var disabled = false
  private lazy val srv = new Bootstrap
  private lazy val bootstrap = srv.group(new NioEventLoopGroup)
    .channel(classOf[NioSocketChannel])
    .option[java.lang.Boolean](ChannelOption.TCP_NODELAY, true)
    .option[java.lang.Boolean](ChannelOption.SO_KEEPALIVE, false)
    .option[java.lang.Boolean](ChannelOption.SO_REUSEADDR, true)
    .option[java.lang.Integer](ChannelOption.SO_LINGER, 0)
    .option[java.lang.Integer](ChannelOption.CONNECT_TIMEOUT_MILLIS, timeout * 1000)
    .handler(new ChannelInitializer[SocketChannel] {
      override def initChannel(ch: SocketChannel) {
        val p = ch.pipeline()
        p.addLast("timeout", new ReadTimeoutHandler(timeout) {
          override def readTimedOut(ctx: ChannelHandlerContext) {
            ctx.channel.close
          }
        })
        engine.foreach(e => p.addLast("ssl", new SslHandler(e)))
        p.addLast("codec", new HttpClientCodec)
        p.addLast("handler", handler)
      }
    })

  private def getPort(url: java.net.URL) = if (url.getPort == -1) url.getDefaultPort else url.getPort

  private def prepare(url: java.net.URL) = {
    bootstrap.duplicate.remoteAddress(new InetSocketAddress(url.getHost, getPort(url)))
  }

  /**
   * Run a GET-Request on the given URL.
   *
   * @param url What could this be?
   * @param headers The mysteries keep piling up!
   */
  def get(url: java.net.URL, headers: Map[String, String] = Map()) = {
    if (disabled) throw new IllegalStateException("HttpClient is already shutdown.")

    val req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, url.getPath)
    req.headers.set(HttpHeaders.Names.HOST, url.getHost + ":" + getPort(url))
    req.headers.set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.CLOSE)
    headers.foreach(f => req.headers.set(f._1, f._2))

    val bootstrap = prepare(url)
    bootstrap.connect().addListener(new ChannelFutureListener() {
      override def operationComplete(cf: ChannelFuture) {
        if (!cf.isSuccess) return
        cf.channel.write(req)
        cf.channel.closeFuture()
      }
    })
  }

  /**
   * Send a PUT/POST-Request on the given URL with body.
   *
   * @param url This is getting weird..
   * @param mime The MIME type of the request
   * @param body ByteArray to be send
   * @param headers
   * @param method HTTP Method to be used
   */
  def post(url: java.net.URL, mime: String, body: Seq[Byte] = Seq(), headers: Map[String, String] = Map(), method: HttpMethod) = {
    if (disabled) throw new IllegalStateException("HttpClient is already shutdown.")

    val content = Unpooled.wrappedBuffer(body.toArray)
    val req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, url.getPath, content)
    req.headers.set(HttpHeaders.Names.HOST, url.getHost + ":" + getPort(url))
    req.headers.set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.CLOSE)
    req.headers.set(HttpHeaders.Names.CONTENT_TYPE, mime)
    req.headers.set(HttpHeaders.Names.CONTENT_LENGTH, content.readableBytes)
    headers.foreach(f => req.headers.set(f._1, f._2))

    val bootstrap = prepare(url)
    bootstrap.connect().addListener(new ChannelFutureListener() {
      override def operationComplete(cf: ChannelFuture) {
        if (!cf.isSuccess) return
        cf.channel.write(req)
        cf.channel.closeFuture()
      }
    })
  }

  /**
   * Send a message to thruput.io endpoint.
   *
   * @param url thruput.io Endpoint
   * @param auth Authentication Key for thruput.io platform
   * @param sign Signing Key for thruput.io platform
   * @param payload Payload to be sent
   */
  def thruput(url: java.net.URL, auth: java.util.UUID, sign: java.util.UUID, payload: String) = {
    if (disabled) throw new IllegalStateException("HttpClient is already shutdown.")

    val headers = Map("X-Io-Auth" -> auth.toString, "X-Io-Sign" -> io.wasted.util.Hashing.sign(sign.toString, payload))
    post(url, "application/json", payload.map(_.toByte), headers, HttpMethod.PUT)
  }

  /**
   * Shutdown this client.
   */
  def shutdown() {
    disabled = true
    srv.shutdown()
  }
}

