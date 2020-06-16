package me.errolalpay.giphyproxy;

public class App {

	public static void main( String[] args ) throws Exception {

		// TODO: consume the command line options for different port numbers, logging etc.

		// instantiate the proxy server
		// allow incoming requests on 8443 to be proxied to api.giphy.com:443 (https)
		ProxyingTcpServer proxyServer = new ProxyingTcpServer( 8443, "api.giphy.com", 443 );

		// start the server
		proxyServer.start();

		// wait for the server to exit
		synchronized ( proxyServer ) {
			proxyServer.wait();
		}

		// done
	}
}
