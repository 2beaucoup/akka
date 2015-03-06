/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.coding

import akka.http.util.{ ByteStringParserStage, StreamUtils }
import akka.stream.scaladsl.Flow
import akka.util.ByteString

/** A stateful object representing ongoing compression. */
abstract class Compressor {
  /**
   * Compresses the given input and returns compressed data. The implementation
   * can and will choose to buffer output data to improve compression. Use
   * `flush` or `compressAndFlush` to make sure that all input data has been
   * compressed and pending output data has been returned.
   */
  def compress(input: ByteString): ByteString

  /**
   * Flushes any output data and returns the currently remaining compressed data.
   */
  def flush(): ByteString

  /**
   * Closes this compressed stream and return the remaining compressed data. After
   * calling this method, this Compressor cannot be used any further.
   */
  def finish(): ByteString

  /** Combines `compress` + `flush` */
  def compressAndFlush(input: ByteString): ByteString
  /** Combines `compress` + `finish` */
  def compressAndFinish(input: ByteString): ByteString
}

trait Compression {
  def newCompressor: Compressor
  def newDecompressor(maxBytesPerChunk: Int): ByteStringParserStage[ByteString]

  val hasCompression: Boolean = true

  def encoder: Flow[ByteString, ByteString, Unit] = {
    val compressor = newCompressor
    def encodeChunk(bytes: ByteString): ByteString = compressor.compressAndFlush(bytes)
    def finish(): ByteString = compressor.finish()
    Flow[ByteString].transform(() ⇒ StreamUtils.byteStringTransformer(encodeChunk, finish))
  }

  def decoder(maxBytesPerChunk: Int): Flow[ByteString, ByteString, Unit] = {
    val decompressor = newDecompressor(maxBytesPerChunk)
    Flow[ByteString].transform(() ⇒ decompressor)
  }
}
