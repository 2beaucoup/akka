/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.coding

import akka.util.ByteString

import java.io.{ InputStream, OutputStream }
import java.util.zip._

class DeflateSpec extends CoderSpec {
  override protected def Coder = Deflate
  override protected def newDecodedInputStream(underlying: InputStream) = new InflaterInputStream(underlying)
  override protected def newEncodedOutputStream(underlying: OutputStream) = new DeflaterOutputStream(underlying)

  override def extraTests(): Unit = {
    "throw early if header is corrupt" in {
      (the[RuntimeException] thrownBy {
        ourDecode(ByteString(0, 1, 2, 3, 4))
      }).getCause should be(a[DataFormatException])
    }
  }
}
