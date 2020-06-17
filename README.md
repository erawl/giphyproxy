# giphyproxy

giphyproxy is a proxy service written in Java that relays HTTPS requests to the GIPHY API. It is written by Errol Alpay.

giphyproxy is composed of primarily 2 java classes; [PeerToPeerServer](https://github.com/erawl/giphyproxy/blob/master/src/main/java/me/errolalpay/giphyproxy/PeerToPeerServer.java) which encapsulates the logic of running a local server that peers 2 connections together, and [ExternalServicePeerToPeerServer](https://github.com/erawl/giphyproxy/blob/master/src/main/java/me/errolalpay/giphyproxy/ExternalServicePeerToPeerServer.java) which is a specialization of [PeerToPeerServer](https://github.com/erawl/giphyproxy/blob/master/src/main/java/me/errolalpay/giphyproxy/PeerToPeerServer.java) that creates a peer connection to an external service, and joins that peer to the original connection. The [PeerToPeerServer](https://github.com/erawl/giphyproxy/blob/master/src/main/java/me/errolalpay/giphyproxy/PeerToPeerServer.java) class is reusable for _any_ TCP peering purpose, regardless of the OSI layer 5-7 protocol.

giphyproxy is written using Java [non-blocking I/O](https://en.wikipedia.org/wiki/Non-blocking_I/O_(Java)), and as such it runs a single thread that can potentially handle thousands of simultaneous connections (although this has not been tested).

An example of calling the GIPHY API to find "i'm excited" gifs:

[https://api.giphy.com/v1/gifs/search?q=im+excited&api_key=3eFQvabDx69SMoOemSPiYfh9FY0nzO9x&offset=0&limit=25](https://api.giphy.com/v1/gifs/search?q=im+excited&api_key=3eFQvabDx69SMoOemSPiYfh9FY0nzO9x&offset=0&limit=25)

An example of calling giphyproxy (which proxies to the GIPHY API) to find "i'm excited" gifs (assumes giphyproxy is running on localhost:8443):

[https://localhost:8443/v1/gifs/search?q=im+excited&api_key=3eFQvabDx69SMoOemSPiYfh9FY0nzO9x&offset=0&limit=25](https://localhost:8443/v1/gifs/search?q=im+excited&api_key=3eFQvabDx69SMoOemSPiYfh9FY0nzO9x&offset=0&limit=25)

Note that the SSL certificate validation will fail in browsers, Postman, curl and any other HTTPS client unless SSL validation is disabled. See _Assumptions, caveats, simplifications and TODOs_.

## How it works
giphyproxy listens for inbound connections like typical TCP servers do. Once an inbound connection is accepted, giphyproxy then opens an outbound connection (called a "peer") to the outbound service (the GIPHY API endpoint). The inbound and outbound connections are then indexed together, forwards and backwards, in a HashMap such that one connection can be easily found from the other, and with predictable performance.

Whenever data is read from one connection, the peer connection is retrieved, and that same data is then written to the peer. This "read from connection, write to peer" works in both directions, regardless of which connection (inbound or outbound) sent the data.

The TCP conversation continues until either connection closes itself. At that time, giphyproxy assumes the conversation is over, and destroys both connections as well as their membership in the index.

## Privacy
giphyproxy has an emphasis on privacy, therefore it doesn't log ip addresses, number of bytes written, date/time, etc. It limits logging to only that which helps us see the service is running properly.

The content of the TCP byte streams are not consumed in any way, therefore is it the client connections' responsibility to know the service they are consuming, and to handle all params, auth, SSL, keep-alives etc.

## Assumptions, caveats, simplifications and TODOs
### Single Threaded
giphyproxy is simplistic in that it runs a single java thread. It listens for new TCP connections, as well as makes outbound connections to GIPHY from that same thread. A more productionized version of giphyproxy might have one thread to listen for new connections, and one thread per processor core to handle socket I/O, and a mechanism to pass socket channels between them. This would maximize speed and CPU efficiency without excessive context switching between threads.

### Logging
Logging is another area where more effort is needed, but has to be built carefully. Logging helps understand the runtime state of the application, but it cannot reveal details of connections like ips, bytes read or written, dates, etc.

### Performance/memory optimizations:
* Use of a HashMap to index connections against their peers. This is discussed above.
* DNS prefetching for api.giphy.com. This eliminates the need to perform one DNS lookup per client connection. However it also assumes the ip for api.giphy.com will not change. A more productionized version would have a separate thread refreshing that ip address periodically, or perhaps code that handles connection failures to the ip for api.giphy.com.
* Shared read buffer: When reading data from one connection and writing to its peer, the data is stored in a shared buffer. This is an optimization that reduces memory waste by allocating a single shared buffer instead of a dedicated  buffer per connection pair. However, it creates a situation where if the data that is read from one connection cannot be immediately written to its peer for any reason, that data is lost forever since the buffer gets reset for immediate reuse.

### GIPHY API Keys
GIPHY requires you to have an API key to call its API. This documentation uses _3eFQvabDx69SMoOemSPiYfh9FY0nzO9x_, which was obtained by [running a search on GIPHY's website](https://giphy.com/search/all-star) and viewing the network tab in Chrome to find the _public_ API key.

### SSL Certificate Validation Failure
When calling giphyproxy using HTTPS, it is important to note that the SSL certificate will fail validation. This is because as the client is negotiating SSL details, that negotiation traffic is sent directly to GIPHY,  and it is GIPHY's SSL certificate that the client receives. However, the _localhost_ hostname in the url when using giphyproxy does not match the _api.giphy.com_ subject name in GIPHY's SSL certificate, hence the failure. To get around this, giphyproxy would have to implement HTTPS and issue its own SSL certificate to the client. Obviously this constitutes a man-in-the-middle and violates the privacy and security requirement for this code.

## Dependencies
To build giphyproxy, you must have Java 1.8 or newer, and [Maven](https://maven.apache.org/) installed on your build machine.

## Compiling and unit tests
giphyproxy is compiled and tested using Java and Maven.
```
mvn package
```
Building giphyproxy automatically executes all [units tests](https://github.com/erawl/giphyproxy/blob/master/src/test/java/me/errolalpay/giphyproxy/PeerToPeerServerTest.java), and currently there are 2:
* A test to ensure proper startup and shutdown of the server
* A test to ensure that the HTTP response code received from some remote HTTP server is the same between 2 scenarios:
    * Calling that server directly
    * Calling that server proxied through giphyproxy

## Running
Requires Java 1.8 or newer.
```
java -cp target/giphyproxy-1.0-SNAPSHOT.jar me.errolalpay.giphyproxy.App
```

## Manual Testing
* Click [here](https://api.giphy.com/v1/gifs/search?q=im+excited&api_key=3eFQvabDx69SMoOemSPiYfh9FY0nzO9x&offset=0&limit=25) to call the GIPHY API from your browser to get a baseline of what to expect from GIPHY.
* Run giphyproxy
```
java -cp target/giphyproxy-1.0-SNAPSHOT.jar me.errolalpay.giphyproxy.App
```
* Setup Postman:
    * From settings, disable SSL Certificate Verification
    * Create a new tab
        * In the Headers subtab, add a header: Host -> api.giphy.com
        * Paste this url into the location: https://localhost:8443/v1/gifs/search?q=im+excited&api_key=3eFQvabDx69SMoOemSPiYfh9FY0nzO9x&offset=0&limit=25
        * Ensure it is a GET request
    * Press Enter
* The result from Postman should be identical to calling GIPHY directly from your browser.
* Note: The caller is responsible for getting an API key from GIPHY (we are using _3eFQvabDx69SMoOemSPiYfh9FY0nzO9x_ in this documentation, which may still work for you). See _Assumptions, caveats, simplifications and TODOs_.
