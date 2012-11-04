package io.wasted.util

import org.slf4j.LoggerFactory
import java.io.{ StringWriter, PrintWriter }

/**
 * This trait enables classes to do easy logging.
 */
trait Logger {
  /**
   * Override this to give your class a custom Logger name.
   */
  protected def loggerName = this.getClass.getName.toString.split("\\.").last

  private[this] lazy val logger = LoggerFactory.getLogger(loggerName)

  /**
   * Implement this method to get your exceptions handled the way you want.
   */
  protected def submitException(trace: String): Unit = {}

  /**
   * Transforms StackTraces into a String using StringWriter.
   */
  implicit def stackTraceToString(throwable: Throwable): String = {
    val w = new StringWriter
    throwable.printStackTrace(new PrintWriter(w))
    w.toString
  }

  /**
   * Prints a message on debug.
   */
  def debug(msg: String, x: Any*) {
    x.foreach(_ match { case msg: Throwable => submitException(msg) case _ => })
    logger.debug(msg.format(x: _*))
  }

  /**
   * Prints a message on info.
   */
  def info(msg: String, x: Any*) {
    x.foreach(_ match { case msg: Throwable => submitException(msg) case _ => })
    logger.info(msg.format(x: _*))
  }

  /**
   * Prints a message on warn.
   */
  def warn(msg: String, x: Any*) {
    x.foreach(_ match { case msg: Throwable => submitException(msg) case _ => })
    logger.warn(msg.format(x: _*))
  }

  /**
   * Prints a message on error.
   */
  def error(msg: String, x: Any*) {
    x.foreach(_ match { case msg: Throwable => submitException(msg) case _ => })
    logger.error(msg.format(x: _*))
  }
}

