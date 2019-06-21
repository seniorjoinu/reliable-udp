## Reliable UDP

[![Build Status](https://travis-ci.com/seniorjoinu/reliable-udp.svg?branch=master)](https://travis-ci.com/seniorjoinu/reliable-udp)
[![](https://jitpack.io/v/seniorjoinu/reliable-udp.svg)](https://jitpack.io/#seniorjoinu/reliable-udp)

### Quick example
```kotlin
// create a pair of sockets
val rudp1 = RUDPSocket()
val rudp2 = RUDPSocket()
                    
val net1Addr = InetSocketAddress(1337)
val net2Addr = InetSocketAddress(1338)

// bind to some address                                
rudp1.bind(net1Addr)
rudp2.bind(net2Addr)

// send some content
var sent = 0

val net1Content = ByteArray(20000) { it.toByte() }
rudp1.send(net1Content, net2Addr) { sent++ } // non-blocking code, provides callbacks, adds data to send queue
rudp1.send(net1Content, net2Addr) { sent++ } // send second time to show multiplexing, the logic inside is done in sequence, so it is absolutely save to increment asynchronously

var received = 0

// there is no blocking "listen" or "run" - you can block your threads in a way you like
while (received < 2 && sent < 2) {
    rudp1.runOnce() // processes data if it available
    rudp2.runOnce()
    
    // try to get data from receive queue
    val data = rudp2.receive()
    if (data != null) received++ // if there is data - increment, if there is no - try again
}

println("Data transmitted")

// close sockets, free resources
rudp1.close()
rudp2.close()
```

### What's inside

1. The main idea is to provide super-flexible and super-fast reliable udp transport that is easy to use.
2. Design is something like [event loop](https://en.wikipedia.org/wiki/Event_loop) that you can control. 
    So no billion threads/coroutines until you really want it. RUDP is thread-safe and supports multiplexing by default.
3. It uses [fountain codes](https://en.wikipedia.org/wiki/Fountain_code) so no real 
    [ARQ](https://en.wikipedia.org/wiki/Automatic_repeat_request) is performed. This makes RUDP (in theory) superior 
    to TCP and maybe to [kcp](https://github.com/skywind3000/kcp).
4. API is almost the same as in standard DatagramSocket. There is no connections and other boring stuff (actually 
    there are, but they are inside). MTU, WINDOW_SIZE and other stuff is configurable as 
    in [kcp](https://github.com/skywind3000/kcp).
5. RUDPSocket uses only one port to receive and send data.

// TODO: add AWS benchmarks

#### Algorithm in short words
1. Sender adds data to the send queue `socket.send(data, address)`
2. When `socket.runOnce()` is invoked
    1. Source data is transformed into small portion of repair packets
    2. Repair packets are written to DatagramSocket sequentially
    3. If there are packets to read from DatagramSocket they are read
    4. For each read repair packet it tries to restore source data
    5. If data is restored completely, ACK packet sent back to sender and data is added to the receive queue
3. Receiver tries to receive data from the receive queue `socket.receive()`

#### API Reference
```kotlin
/**
 * The socket itself. Just create one of those and use it to send and receive data over the network.
 *
 * @param mtuBytes [Int] - minimum MTU of all those router between you and someone you send data to
 * @param windowSizeBytes [Int] - pieces of data are sent in small groups with total size of this value
 * @param congestionControlTimeoutMs [Long] - after each group of [windowSizeBytes] is sent, socket waits until
 *  [congestionControlTimeoutMs] elapsed before sending another [windowSizeBytes] of that data
 * @param cleanUpTimeoutMs [Long] - the lesser this value is, the more frequent socket will clean up itself
 */
class RUDPSocket(
    val mtuBytes: Int = 1200,
    val windowSizeBytes: Int = 4800,
    val congestionControlTimeoutMs: Long = 100,
    val cleanUpTimeoutMs: Long = 1000 * 60 * 10
)

/**
 * Binds to the local address. Before this call you're unable to receive packets.
 *
 * @param on [InetSocketAddress] - address to bind
 */
fun bind(on: InetSocketAddress)

/**
 * Destroys all contexts and closes this socket - after this you should create another one to work with
 */
fun close()

/**
 * Is socket closed
 *
 * @return [Boolean]
 */
fun isClosed(): Boolean

/**
 * Adds data in processing queue for send.
 *
 * @param data [ByteBuffer] - normalized (flipped) data
 * @param to [InetSocketAddress] - address to send data to
 * @param stop lambda returning [Boolean] - called on each processing loop iteration, if returns true - sending is
 *  canceled
 * @param complete lambda returning [Void] - called when send is completed successfully (if sending is canceled,
 *  this callback is not executed)
 */
fun RUDPSocket.send(
    data: ByteBuffer, 
    to: InetSocketAddress, 
    stop: ExitCallback = { false }, 
    complete: CompleteCallback = {}
)

/**
 * [RUDPSocket.send] but instead of [ByteBuffer] it sends [ByteArray]
 *
 * @param data [ByteArray] - input data
 * @param to [InetSocketAddress] - receiver
 * @param dataSizeBytes [Int] - if not specified [ByteArray.size] will be used
 * @param exit [ExitCallback]
 * @param complete [CompleteCallback]
 */
fun RUDPSocket.send(
    data: ByteArray,
    to: InetSocketAddress,
    dataSizeBytes: Int = 0,
    exit: ExitCallback = { false },
    complete: CompleteCallback = {}
)

/**
 * Tries to retrieve some data from receive queue.
 *
 * @return [QueuedDatagramPacket] - if there is a data returns packet, otherwise - [null]
 */
fun RUDPSocket.receive(): QueuedDatagramPacket?

/**
 * Blocks current thread until it receives something from the socket.
 *
 * @param timeoutMs [Long] - if not specified runs forever
 *
 * @return [QueuedDatagramPacket]
 * @throws [TimeoutException]
 */
@Throws(TimeoutException::class)
fun RUDPSocket.receiveBlocking(timeoutMs: Long = 0): QueuedDatagramPacket

/**
 * Runs processing loop once.
 *
 * Loop consists of three stages:
 *  1. Clean up
 *  2. Processing send
 *  3. Processing receive
 */
fun runOnce()

/**
 * Blocks current thread running [RUDPSocket]'s processing loop until [exit] condition is met.
 *
 * @param exit lambda () -> [Boolean] - when returns true processing loop completes (it still be run after)
 */
fun RUDPSocket.runBlocking(exit: () -> Boolean = { false })
```

### Installation

Use [Jitpack](https://jitpack.io/)

For example usage see [integration-example-project](https://github.com/seniorjoinu/reliable-udp-integration).

For advanced usage see [seniorjoinu/prodigy](https://github.com/seniorjoinu/prodigy).
