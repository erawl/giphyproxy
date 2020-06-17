package me.errolalpay.giphyproxy;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;

import org.junit.Test;

/**
 * Unit tests for the PeerToPeerServer class.
 */
public class PeerToPeerServerTest {

	/**
	 * Tests the startup and shutdown of the server
	 *
	 * @throws Exception
	 */
	@Test
	public void startAndStopTest() throws Exception {

		PeerToPeerServer peerToPeerServer = new ExternalServicePeerToPeerServer( 8080, "localhost", 8081 );

		// start the server
		synchronized ( peerToPeerServer ) {
			peerToPeerServer.start();
			peerToPeerServer.wait();
		}

		// shutdown and wait for the server to exit
		synchronized ( peerToPeerServer ) {
			peerToPeerServer.shutdown();
			peerToPeerServer.wait();
		}

		assertTrue( true );
	}

	@Test
	public void ensureProxyDoesntChangeResponse() throws Exception {

		String targetHostName = "junit.sourceforge.net";

		// get the response code using a non-proxied connection
		int normalResponseCode = getSimpleHttpResponseCode(
				"http://" + targetHostName + "/",
				targetHostName );

		PeerToPeerServer peerToPeerServer = null;
		try {
			peerToPeerServer = new ExternalServicePeerToPeerServer( 8080, targetHostName, 80 );

			// start the server
			synchronized ( peerToPeerServer ) {
				peerToPeerServer.start();
				peerToPeerServer.wait();
			}

			// assert that the normal response code is the same that we get with a proxied connection to the same server
			assertTrue( normalResponseCode == getSimpleHttpResponseCode(
					"http://localhost:8080/",
					targetHostName ) );

		} finally {

			// shutdown and wait for the server to exit
			synchronized ( peerToPeerServer ) {
				peerToPeerServer.shutdown();
				peerToPeerServer.wait();
			}
		}
	}

	private int getSimpleHttpResponseCode( String url, String hostName ) throws MalformedURLException, IOException {

		HttpURLConnection connection = null;
		try {

			connection = (HttpURLConnection) new URL( url ).openConnection();
			connection.setRequestMethod( "GET" );
			connection.addRequestProperty( "Accept", "image/webp,image/apng,image/*,*/*;q=0.8" );
			connection.addRequestProperty( "Accept-encoding", "gzip, deflate, br" );
			connection.addRequestProperty( "Accept-language", "en-US,en;q=0.9" );
			connection.addRequestProperty( "Connection", "close" );
			connection.addRequestProperty( "Cache-control", "no-cache" );
			connection.addRequestProperty( "Host", hostName );
			connection.setRequestProperty(
					"User-Agent",
					"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_13_6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/83.0.4103.61 Safari/537.36" );

			return connection.getResponseCode();

		} finally {

			try {
				connection.getInputStream().close();
			} catch ( Exception ex ) {
			}
		}

	}

}
