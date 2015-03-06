/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.coding

import java.io.{ OutputStream, InputStream }

class NoCodingSpec extends CoderSpec {
  override protected def Coder = NoCoding
  override protected def corruptInputCheck = false
  override protected def newEncodedOutputStream(underlying: OutputStream) = underlying
  override protected def newDecodedInputStream(underlying: InputStream) = underlying
}
