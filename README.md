## Reliable UDP

[![Build Status](https://travis-ci.com/seniorjoinu/reliable-udp.svg?branch=master)](https://travis-ci.com/seniorjoinu/reliable-udp)

Reliable UDP for Kotlin using `Wirehair` fountain codes and coroutines

### Abstract

##### What is fec?
FEC stands for Forward Error Correction - technique that uses erasure codes to recover missed data after transmission.
Let's suppose you have N chunks of data that you want to transfer to your friend via unreliable transport. You know that
your transport can have no more but 33% packet loss rate. Using FEC you encode N chunks of data into 4N/3 chunks of data
to prevent it from loss and send all these chunks to your friend. Your friend receives from N to 4N/3 of chunks due to
packet loss, but that's enough. He now can recover original N chunks just from what he's got. So, your transport protocol
can implement no ARQ technique but be as reliable as you want.

FEC is kinda old concept and it evolves just like any other good technique. The most recent improvement in this field is
called Fountain Codes. The main feature of this improvement is infinite number of repair chunks that you can create for
your constant-sized data. Let's suppose the same situation with you and your friend from above, but now packet loss rate
is unknown - it can be 10% or 90%, or 99% - this is very natural for real networks. In this situation using Fountain Codes 
you can constantly send your friend repair blocks - they never end. When your friend receives enough (most of the times - N)
repair chunks, he responds you with just one ACK saying that he completely received your data and you can finish transmission
from your side.

This repository is my Kotlin implementation of reliable UDP using fountain codes and coroutines.

##### Best fountain codes
I surfed github and google a lot, but found only one library that I really like. 
It called [catid/wirehair](https://github.com/catid/wirehair).
What I really like about it is it's speed and clear API - Christopher is a really good developer.

I've written a Kotlin wrapper for it [seniorjoinu/wirehair-wrapper](https://github.com/seniorjoinu/wirehair-wrapper)

##### Design
As you can see this library is called reliable **UDP**. So it designed as regular UDP - you send some data packet to some
address without connections, streams and other TCP shit.

### Installation
1. Make sure you understand how to put `wirehair` binaries into your project correctly - [link](https://github.com/seniorjoinu/wirehair-wrapper#details)
2. Use [Jitpack](https://jitpack.io/)

### Example
Right now there is only one (*configurable*) implementation of `RUDPSocket`.
```kotlin
// coroutines so use proper context
runBlocking {
    // set addresses
    val net1Addr = InetSocketAddress("localhost", 1337)
    val net2Addr = InetSocketAddress("localhost", 1338)
    
    // create some message
    val net1Content = ByteArray(100000) { it.toByte() }
    
    // create and bind sockets (1400 - is MTU)
    val rudp1 = ConfigurableRUDPSocket(1400)
    rudp1.bind(net1Addr)
    
    val rudp2 = ConfigurableRUDPSocket(1400)
    rudp2.bind(net2Addr)

    // start listening for messages
    launch(Dispatchers.IO) { rudp1.listen() }
    // you should always bind and listen on socket
    launch(Dispatchers.IO) { rudp2.listen() }

    // add callback to execute come code when message received
    rudp2.onMessage { buffer, from ->
        val bytes = ByteArray(buffer.limit())
        buffer.get(bytes)

        rudp2.close()
    }

    // send message asynchronously
    launch {
        rudp1.send(
            net1Content.toDirectByteBuffer(),
            net2Addr,
            fctTimeoutMsProvider = { 50 },
            windowSizeProvider = { 1400 }
        )
    }
}
```

### Help
Submit and issue or suggest a PR
