package com.github.huntc.landlord

import java.io.{ FileDescriptor, IOException, InputStream, PrintStream, PrintWriter }
import java.net.InetAddress
import java.security.Permission
import java.util.function.BiConsumer
import java.util.{ Collection => JCollection, Enumeration => JEnumeration, Map => JMap, Properties, Set => JSet }

import scala.collection.immutable.HashMap

/**
 * A ThreadGroupMapping trait provides a mutable thread safe map that keys
 * by thread groups associating an element of type A. The `init` method must
 * be called at least once for each thread group that will be using the element.
 */
trait ThreadGroupMapping[A] {
  @volatile protected var threadGroupElements = HashMap.empty[ThreadGroup, A]

  protected val _fallback: A
  def fallback: A = _fallback

  def init(e: A): Unit =
    threadGroupElements.synchronized {
      threadGroupElements = threadGroupElements + (Thread.currentThread.getThreadGroup -> e)
    }

  def destroy(): Unit =
    threadGroupElements.synchronized {
      threadGroupElements = threadGroupElements - Thread.currentThread.getThreadGroup
    }

  def get: A =
    threadGroupElements.getOrElse(Thread.currentThread.getThreadGroup, fallback)
}

/**
 * An input stream that reads from any input stream associated with the current
 * thread's thread group, or the fallback input stream in case there is none.
 */
class ThreadGroupInputStream(in: InputStream)
  extends InputStream
  with ThreadGroupMapping[InputStream] {

  @volatile protected var closed = false

  protected val _fallback: InputStream = in

  def signalClose(): Unit = {
    closed = true
  }

  override def available(): Int =
    get.available()

  override def close(): Unit =
    get.close()

  override def mark(readlimit: Int): Unit =
    get.mark(readlimit)

  override def markSupported(): Boolean =
    get.markSupported()

  override def read(): Int =
    catchIfClosed(get.read())

  override def read(b: Array[Byte]): Int =
    catchIfClosed(get.read(b))

  override def read(b: Array[Byte], off: Int, len: Int): Int =
    catchIfClosed(get.read(b, off, len))

  override def reset(): Unit =
    get.reset()

  override def skip(n: Long): Long =
    get.skip(n)

  protected def catchIfClosed(f: => Int): Int = {
    try {
      f
    } catch {
      case _: IOException if closed => -1
    }
  }
}

/**
 * A print stream that writes to any print stream associated with the current
 * thread's thread group, or the fallback print stream in case there is none.
 */
class ThreadGroupPrintStream(out: PrintStream)
  extends PrintStream(out)
  with ThreadGroupMapping[PrintStream] {

  protected val _fallback: PrintStream = out

  override def checkError(): Boolean =
    get.checkError()

  override def close(): Unit =
    get.close()

  override def flush(): Unit =
    get.flush()

  override def write(buf: Array[Byte], off: Int, len: Int): Unit =
    get.write(buf, off, len)

  override def write(b: Int): Unit =
    get.write(b)
}

/**
 * Properties to be associated with the current thread's thread group.
 */
class ThreadGroupProperties(props: Properties)
  extends Properties
  with ThreadGroupMapping[Properties] {

  protected val _fallback: Properties = props

  override def elements(): JEnumeration[AnyRef] =
    super.get.elements()

  override def contains(value: Any): Boolean =
    super.get.contains(value)

  override def containsKey(key: Any): Boolean =
    super.get.containsKey(key)

  override def containsValue(value: Any): Boolean =
    super.get.containsValue(value)

  override def entrySet(): JSet[JMap.Entry[AnyRef, AnyRef]] =
    super.get.entrySet()

  override def forEach(action: BiConsumer[_ >: AnyRef, _ >: AnyRef]): Unit =
    super.get.forEach(action)

  override def get(key: Any): AnyRef =
    super.get.get(key)

  override def getOrDefault(key: Any, defaultValue: AnyRef): AnyRef =
    super.get.getOrDefault(key, defaultValue)

  override def getProperty(key: String): String =
    super.get.getProperty(key)

  override def getProperty(key: String, defaultValue: String): String =
    super.get.getProperty(key, defaultValue)

  override def isEmpty: Boolean =
    super.get.isEmpty

  override def keys(): JEnumeration[AnyRef] =
    super.get.keys()

  override def keySet(): JSet[AnyRef] =
    super.get.keySet()

  override def list(out: PrintStream): Unit =
    super.get.list(out)

  override def list(out: PrintWriter): Unit =
    super.get.list(out)

  override def propertyNames(): JEnumeration[_] =
    super.get.propertyNames()

  override def size(): Int =
    super.get.size()

  override def stringPropertyNames(): JSet[String] =
    super.get.stringPropertyNames()

  override def values(): JCollection[AnyRef] =
    super.get.values()
}

class ThreadGroupSecurityManager(s: SecurityManager)
  extends SecurityManager
  with ThreadGroupMapping[SecurityManager] {

  protected val _fallback: SecurityManager =
    if (s == null)
      new SecurityManager() {
        override def checkPermission(perm: Permission): Unit = ()
        override def checkPermission(perm: Permission, context: Object): Unit = ()
      } // If there's no security manager then we provide a permissive one (which means the same)
    else
      s

  override def checkAccept(host: String, port: Int): Unit =
    get.checkAccept(host, port)

  override def checkAccess(t: Thread): Unit =
    get.checkAccess(t)

  override def checkAccess(g: ThreadGroup): Unit =
    get.checkAccess(g)

  override def checkAwtEventQueueAccess(): Unit =
    get.checkAwtEventQueueAccess()

  override def checkConnect(host: String, port: Int): Unit =
    get.checkConnect(host, port)

  override def checkConnect(host: String, port: Int, context: Object): Unit =
    get.checkConnect(host, port, context)

  override def checkCreateClassLoader(): Unit =
    get.checkCreateClassLoader()

  override def checkDelete(file: String): Unit =
    get.checkDelete(file)

  override def checkExec(cmd: String): Unit =
    get.checkExec(cmd)

  override def checkExit(status: Int): Unit =
    get.checkExit(status)

  override def checkLink(lib: String): Unit =
    get.checkLink(lib)

  override def checkListen(port: Int): Unit =
    get.checkListen(port)

  override def checkMemberAccess(clazz: Class[_], which: Int): Unit =
    get.checkMemberAccess(clazz, which)

  override def checkMulticast(maddr: InetAddress): Unit =
    get.checkMulticast(maddr)

  override def checkMulticast(maddr: InetAddress, ttl: Byte): Unit =
    get.checkMulticast(maddr, ttl)

  override def checkPackageAccess(pkg: String): Unit =
    get.checkPackageAccess(pkg)

  override def checkPackageDefinition(pkg: String): Unit =
    get.checkPackageDefinition(pkg)

  override def checkPermission(perm: Permission): Unit =
    get.checkPermission(perm)

  override def checkPermission(perm: Permission, context: Object): Unit =
    get.checkPermission(perm, context)

  override def checkPrintJobAccess(): Unit =
    get.checkPrintJobAccess()

  override def checkPropertiesAccess(): Unit =
    get.checkPropertiesAccess()

  override def checkPropertyAccess(key: String): Unit =
    get.checkPropertyAccess(key)

  override def checkRead(fd: FileDescriptor): Unit =
    get.checkRead(fd)

  override def checkRead(file: String): Unit =
    get.checkRead(file)

  override def checkRead(file: String, context: Object): Unit =
    get.checkRead(file)

  override def checkSecurityAccess(target: String): Unit =
    get.checkSecurityAccess(target)

  override def checkSetFactory(): Unit =
    get.checkSetFactory()

  override def checkSystemClipboardAccess(): Unit =
    get.checkSystemClipboardAccess()

  override def checkTopLevelWindow(window: Object): Boolean =
    get.checkTopLevelWindow(window)

  override def checkWrite(fd: FileDescriptor): Unit =
    get.checkWrite(fd)

  override def checkWrite(file: String): Unit =
    get.checkWrite(file)

  override def getInCheck: Boolean =
    get.getInCheck

  override def getSecurityContext: Object =
    get.getSecurityContext

  override def getThreadGroup: ThreadGroup =
    get.getThreadGroup
}
