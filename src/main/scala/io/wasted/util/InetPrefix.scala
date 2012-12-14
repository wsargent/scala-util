package io.wasted.util

import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Helper Object for creating InetPrefix-objects.
 */
object InetPrefix {
  def apply(prefix: InetAddress, prefixLen: Int): InetPrefix = {
    if (prefix.getAddress.length == 16)
      new Inet6Prefix(prefix, prefixLen)
    else
      new Inet4Prefix(prefix, prefixLen)
  }

  def inetAddrToLong(addr: InetAddress): Long =
    (0L to 3L).toArray.foldRight(0L)((i, ip) => ip | (addr.getAddress()(3 - i.toInt) & 0xff).toLong << i * 8)

}

trait InetPrefix {

  /**
   * Base IP-Address of the Prefix.
   */
  val prefix: InetAddress

  /**
   * Length of the Prefix. 0-32 for IPv4 and 0-128 for IPv6.
   */
  val prefixLen: Int

  /**
   * Either 4 for IPv4 or 6 for IPv6.
   */
  val ipVersion: Int

  /**
   * Check if the given InetAddress is contained in this prefix.
   */
  def contains(addr: InetAddress): Boolean

  private def prefixAddr() = prefix.getHostAddress
  override def toString() = s"$prefixAddr/$prefixLen"
}

/**
 * Inet6Prefix object to hold information about an IPv6 Prefix.
 *
 * Currently only containment checks are implemented.
 *
 * @param prefix Base-Address for the Prefix
 * @param prefixLen Length of this Prefix in CIDR notation
 */
class Inet6Prefix(val prefix: InetAddress, val prefixLen: Int) extends InetPrefix {
  val ipVersion = 6
  if (prefixLen < 0 || prefixLen > 128)
    throw new UnknownHostException(s"$prefixLen is not a valid IPv6 Prefix-Length (0-128)")

  private val network: Array[Byte] = prefix.getAddress
  private val netmask: Array[Byte] = {
    var netmask: Array[Byte] = Array.fill(16)(0xff.toByte)
    val maskBytes: Int = prefixLen / 8
    netmask(maskBytes) = (0xff.toByte << 8 - (prefixLen % 8)).toByte
    for (i <- maskBytes + 1 to (128 / 8) - 1) netmask(i) = 0
    netmask
  }

  /**
   * Check if the given InetAddress is contained in this IPv6 prefix.
   */
  def contains(addr: InetAddress): Boolean = {
    if (addr.getAddress.length != 16)
      throw new UnknownHostException("Inet6Prefix cannot check against Inet4Address")

    val candidate = addr.getAddress
    for (i <- 0 to netmask.length - 1)
      if ((candidate(i) & netmask(i)) != (network(i) & netmask(i))) return false
    true
  }
}

/**
 * Inet4Prefix object to hold information about an IPv4 Prefix.
 *
 * Currently only containment checks are implemented.
 *
 * @param prefix Base-Address for the Prefix
 * @param prefixLen Length of this Prefix in CIDR notation
 */
class Inet4Prefix(val prefix: InetAddress, val prefixLen: Int) extends InetPrefix {
  val ipVersion = 4
  if (prefixLen < 0 || prefixLen > 32)
    throw new UnknownHostException(s"$prefixLen is not a valid IPv4 Prefix-Length (0-32)")

  private val netmask: Long = (((1L << 32) - 1) << (32 - prefixLen)) & 0xFFFFFFFFL
  private val start: Long = InetPrefix.inetAddrToLong(prefix) & netmask
  private val stop: Long = start + (0xFFFFFFFFL >> prefixLen)

  /**
   * Check if the given InetAddress is contained in this IPv4 prefix.
   * @param addr The InetAddr which is to be checked.
   */
  def contains(addr: InetAddress): Boolean = {
    if (addr.getAddress.length != 4)
      throw new UnknownHostException("Inet4Prefix cannot check against Inet6Address")

    val candidate = InetPrefix.inetAddrToLong(addr)
    candidate >= start && candidate <= stop
  }
}

