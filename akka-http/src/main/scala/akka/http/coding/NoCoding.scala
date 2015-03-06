/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.coding

import akka.http.model._
import akka.stream.scaladsl.Flow
import akka.util.ByteString
import headers.HttpEncodings

import scala.collection.immutable

/**
 * An encoder and decoder for the HTTP 'identity' encoding.
 */
object NoCoding extends Coder {
  override val encoding = HttpEncodings.identity
  override val hasCompression: Boolean = false
  override val encoder = Flow[ByteString]
  override def addEncoding(headers: immutable.Seq[HttpHeader]) = headers
  override def decoder(maxBytesPerChunk: Int) = Flow[ByteString]
  override def removeEncoding(headers: immutable.Seq[HttpHeader]) = headers
}
