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

val net1Content = ByteArray(20000) { it.toByte() }

// running coroutines in some scope (as you might notice, it uses only one thread)
runBlocking {
    // start sockets
    launch { rudp1.runSuspending() }
    launch { rudp2.runSuspending() }

    // send-receive some stuff
    coroutineScope {
        launch { rudp1.send(net1Content, net2Addr) }
        launch { rudp1.send(net1Content, net2Addr) }
        launch { rudp2.receive() }
        launch { rudp2.receive() }
    } 
    // <-- at this moment all send() and receive() are completed, thanks to the structured concurrency

    // stop sockets
    coroutineContext.cancelChildren()
}

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

// TODO: add AWS benchmark

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
fun RUDPSocket.bind(on: InetSocketAddress)

/**
 * Destroys all contexts and closes this socket - after this you should create another one to work with
 */
fun RUDPSocket.close()

/**
 * Is socket closed
 *
 * @return [Boolean]
 */
fun RUDPSocket.isClosed(): Boolean

/**
 * Adds data in processing queue for send. Suspends until data is certainly sent. Can be canceled.
 *
 * @param data [ByteBuffer] - normalized (flipped) data
 * @param to [InetSocketAddress] - address to send data to
 *
 * @return [RUDPSendContext]
 */
suspend fun RUDPSocket.send(data: ByteBuffer, to: InetSocketAddress): RUDPSendContext

/**
 * [RUDPSocket.send] but instead of [ByteBuffer] it sends [ByteArray]
 *
 * @param data [ByteArray] - input data
 * @param to [InetSocketAddress] - receiver
 *
 * @return [RUDPSendContext]
 */
suspend fun RUDPSocket.send(data: ByteArray, to: InetSocketAddress): RUDPSendContext

/**
 * Suspends until there is a packet to receive
 *
 * @return [QueuedDatagramPacket]
 */
suspend fun RUDPSocket.receive(): QueuedDatagramPacket

/**
 * Executes [RUDPSocket.runOnce] in loop until coroutine is not canceled
 */
suspend fun RUDPSocket.runSuspending()

/**
 * Runs processing loop once. Suspends if nobody receives packets.
 *
 * Loop consists of three stages:
 *  1. Clean up
 *  2. Processing send
 *  3. Processing receive
 */
suspend fun RUDPSocket.runOnce()
```

#### Algorithm in short words
1. Sender adds data to the send queue `socket.send(data, address)`
2. When `socket.runOnce()` is invoked
    1. Source data is transformed into small portion of repair packets
    2. Repair packets are written to DatagramSocket sequentially
    3. If there are packets to read from DatagramSocket they are read
    4. For each read repair packet it tries to restore source data
    5. If data is restored completely, ACK packet sent back to sender and data is added to the receive queue
3. Receiver tries to receive data from the receive queue `socket.receive()`

### Installation
Use [Jitpack](https://jitpack.io/)

### Examples
For example usage in other app see [integration-example-project](https://github.com/seniorjoinu/reliable-udp-integration)

For advanced usage see [seniorjoinu/prodigy](https://github.com/seniorjoinu/prodigy)

Also see `test` dir in this repo

### Contribution
If you want to improve RUDP but don't know where to start, there is a [project](https://github.com/seniorjoinu/reliable-udp/projects/1).
Pick any task you like and propose a PR.
