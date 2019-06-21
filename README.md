## Reliable UDP

[![Build Status](https://travis-ci.com/seniorjoinu/reliable-udp.svg?branch=master)](https://travis-ci.com/seniorjoinu/reliable-udp)
[![](https://jitpack.io/v/seniorjoinu/reliable-udp.svg)](https://jitpack.io/#seniorjoinu/reliable-udp)

Multiplexed, concurrency model agnostic reliable UDP for Kotlin using fountain codes.

### Quick example
```kotlin
// create a pair of sockets
val rudp1 = RUDPSocket()
val rudp2 = RUDPSocket()
                    
val net1Addr = InetSocketAddress("localhost", 1337)
val net2Addr = InetSocketAddress("localhost", 1338)

// bind to some address                                
rudp1.bind(net1Addr)
rudp2.bind(net2Addr)

// send some content
val net1Content = ByteArray(20000) { it.toByte() }
rudp1.send(net1Content.toDirectByteBuffer(), net2Addr) // non-blocking code, provides callbacks, adds data to send queue

// there is no blocking "listen" or "run" - you can block your threads in a way you like
while (true) {
    rudp1.runOnce() // processes data if it available
    rudp2.runOnce()
    
    // try to get data from receive queue
    val k = rudp2.receive()
    if (k != null) break // if there is data - unblock, if there is no - try again
}

println("Data transmitted")

// close sockets, free resources
rudp1.close()
rudp2.close()
```

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

##### Best fountain codes
I surfed github and google a lot, but found only one library that I really like. 
It called [catid/wirehair](https://github.com/catid/wirehair).
What I really like about it is it's speed and clear API - Christopher is a really good developer.

I've written a Kotlin wrapper for it [seniorjoinu/wirehair-wrapper](https://github.com/seniorjoinu/wirehair-wrapper)

### Installation
Use [Jitpack](https://jitpack.io/)

### Help
Submit an issue or suggest a PR
