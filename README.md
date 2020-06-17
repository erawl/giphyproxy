# giphyproxy

giphyproxy is a proxy service written in Java that relays TCP requests to the GIPHY API. It is written by Errol Alpay.

An example of calling the GIPHY API to find "all star" gifs:

[https://api.giphy.com/v1/gifs/search?q=all%20star&api_key=3eFQvabDx69SMoOemSPiYfh9FY0nzO9x&offset=0&limit=25](https://api.giphy.com/v1/gifs/search?q=all%20star&api_key=3eFQvabDx69SMoOemSPiYfh9FY0nzO9x&offset=0&limit=25)

An example of calling giphyproxy (which proxies to the GIPHY API) to find "all star" gifs (assumes giphyproxy is running on localhost):

[https://localhost:8443/v1/gifs/search?q=all%20star&api_key=3eFQvabDx69SMoOemSPiYfh9FY0nzO9x&offset=0&limit=25](https://localhost:8443/v1/gifs/search?q=all%20star&api_key=3eFQvabDx69SMoOemSPiYfh9FY0nzO9x&offset=0&limit=25)

## How it works
giphyproxy listens for inbound connections like typical TCP servers do. Once an inbound connection is accepted, giphyproxy then opens an outbound connection (called a "companion") to the outbound service (the GIPHY API endpoint). The two connections are then indexed together, forwards and backwards, in a HashMap such that the companion connection can be found with predictable performance. Whenever data is read from one connection, the companion connection is retrieved, and that same data is then written to the companion. This "read from connection, write to companion" works in both directions, regardless of which connection (inbound or outbound) sent the data. This read-write, back-and-forth TCP conversation continues until either connection closes itself. At that time, giphyproxy assumes the conversation is over, and destroys both connections as well as their membership in the index.

## The special sauce
The "read from connection, write to companion" concept simplifies the code, since after both connections are established, the proxy server doesn't keep track of which was the inbound and which was the outbound. Both connections are treated equally, in that the server reads from one, writes to the other. And if either connection closes itself, the proxy server tears down both. This is implemented in the code by indexing each connection (both inbound and outbound) with its companion connection, and when one connection has data available, it is read and written immediately to its companion.

## Privacy
giphyproxy has an emphasis on privacy, therefore it doesn't log ip addresses, number of bytes written, date/time, etc. It limits logging to only that which helps us see the service is running properly.

The content of the TCP byte streams are not consumed in any way, therefore is it the client connections' responsibility to know the service they are consuming, and to handle all params, auth, SSL, keep-alives etc.

## Assumptions, caveats, simplifications and TODOs
giphyproxy is simplistic in that it runs a single java thread. It listens for new TCP connections, as well as makes outbound connections to GIPHY from that same thread. A more productionized version of giphyproxy would have one thread to listen for new connections, and one thread per processor core to handle socket I/O, and a mechanism to pass socket channels between them. This would maximize speed and CPU efficiency without excessive context switching between threads.

Logging is another area where more effort is needed, but has to be built carefully. Logging helps understand the runtime state of the application, but it cannot reveal details of connections like ips, bytes read or written, dates, etc.

Two small performance optimizations are made in giphyproxy:
* Use of a HashMap to index connections against their companions. This is discussed above.
* DNS prefetching for api.giphy.com. This eliminates the need to perform one DNS lookup per client connection. However it also assumes the ip for api.giphy.com will not change. A more productionized version would have a separate thread refreshing that ip address periodically, or perhaps code that handles connection failures to the ip for api.giphy.com.

## Dependencies
To build giphyproxy, you must have Java 1.8 or newer, and [Maven](https://maven.apache.org/) installed on your build machine.

## Compiling and unit tests
giphyproxy is compiled and tested using Java and Maven.
```
mvn package
```

## Running
Requires Java 1.8 or newer.
```
java -cp target/giphyproxy-1.0-SNAPSHOT.jar me.errolalpay.giphyproxy.App
```

## Testing
Click [here](https://api.giphy.com/v1/gifs/search?q=all%20star&api_key=3eFQvabDx69SMoOemSPiYfh9FY0nzO9x&offset=0&limit=25) to call the GIPHY API from your browser.

Click [here](https://localhost:8443/v1/gifs/search?q=all%20star&api_key=3eFQvabDx69SMoOemSPiYfh9FY0nzO9x&offset=0&limit=25) to make the same call to giphyproxy, and the results should be the same (assumes giphyproxy is running on localhost).

The caller is responsible for getting an API key from GIPHY (we are using _3eFQvabDx69SMoOemSPiYfh9FY0nzO9x_ in this documentation, which may still work for you). See assumptions.
