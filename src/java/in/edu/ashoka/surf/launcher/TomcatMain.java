/*
 Copyright (C) 2012 The Stanford MobiSocial Laboratory

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/
package in.edu.ashoka.surf.launcher;

import edu.stanford.ejalbert.BrowserLauncher;
import edu.stanford.ejalbert.exception.BrowserLaunchingInitializingException;
import edu.stanford.ejalbert.exception.UnsupportedOperatingSystemException;
import org.apache.catalina.Context;
import org.apache.catalina.Session;
import org.apache.catalina.startup.Tomcat;
import org.apache.commons.cli.*;
import org.apache.log4j.PropertyConfigurator;
import org.apache.tomcat.util.http.fileupload.FileUtils;

import javax.servlet.ServletException;
import javax.servlet.http.HttpSession;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Properties;

class Splash extends Frame implements ActionListener {
	private Graphics2D g;
	SplashScreen splash = SplashScreen.getSplashScreen();

	void close() { splash.close(); }

	void setSplashText(String text) {
		if (splash == null)
			return;

		// clear the previous text
		g.setColor(new Color(1, 117, 188)); // #0175bc
		g.fillRect(300, 175, ((int) splash.getBounds().getWidth()) - 300, 40); // x=300 is where the logo starts, so we left-align to it. width is usually 795
		g.setPaintMode();

		// write the new text
		g.setColor(Color.WHITE); // #0175bc
		g.drawString(text, 300, 200);
//		g.setFont(new Font("Serif", Font.PLAIN, 12));
		splash.update();
	}

	public Splash() {
		final SplashScreen splash = (System.getProperty("nobrowseropen") == null) ? SplashScreen.getSplashScreen() : null;
		if (splash == null) {
			System.out.println("SplashScreen.getSplashScreen() returned null");
			return;
		}
		Rectangle r = splash.getBounds();
		g = splash.createGraphics();
		if (g == null) {
			System.out.println("splash.createGraphics() returned null");
			return;
		}
		System.out.println ("splash url = " + splash.getImageURL() + " w=" + r.getWidth() + " h=" + r.getHeight() + " size=" + r.getSize() + " loc=" + r.getLocation());
//		setVisible(true);
//		toFront();
	}

	public void actionPerformed(ActionEvent ae) {
		// System.exit(0);
	}

	static WindowListener closeWindow = new WindowAdapter(){
		public void windowClosing(WindowEvent e){
			e.getWindow().dispose();
		}
	};
}

/** main launcher class for tomcat with the surf.war webapp */
public class TomcatMain {
	public static String SURF_PROPS_FILE = System.getProperty("user.home") + File.separator + "surf.properties";

	static PrintStream savedSystemOut, savedSystemErr;
	static PrintStream out = System.out, err = System.err;
	static BrowserLauncher launcher;
	static String preferredBrowser;
	static Tomcat server;
	static String BASE_URL, APP_CHECK_URL, WEBAPP_NAME = "surf";
	static boolean debug = false;
	//static String debugFile;
	static final int TIMEOUT_SECS = 60;
	static Context SURF_WEBAPP; // this will be used by shutdown thread to find sessions and close them down cleanly

	static String SETTINGS_DIR = System.getProperty ("user.home");

	private static final int MB = 1024 * 1024;
	final static int DEFAULT_PORT = 9099;
	static int PORT = DEFAULT_PORT;
	private static Desktop desktop;
	private static boolean browserOpen = true;
    private static in.edu.ashoka.surf.launcher.Splash SPLASH;

	static { if (System.getProperty("nobrowseropen") == null) { desktop = Desktop.getDesktop(); } } // necessary to do this early, causes problems with java 7 (both for taskbar icon and launching a browser) if you try and do it later

	private static void tellUser (String s) { out.println (s); if (SPLASH != null) SPLASH.setSplashText(s); }

	// reads the resourceName from the classpath, copies it to rootDir. clears it if it already existed
	private static void copyResource(String resourceName, String rootDir) throws IOException, ServletException
	{
        String file = rootDir + File.separatorChar + resourceName;

        // extract the war to tmpdir
        {
            final URL resourceUrl = in.edu.ashoka.surf.launcher.TomcatMain.class.getClassLoader().getResource(resourceName);
            if (resourceUrl == null) {
                tellUser("Sorry! Unable to locate file on classpath: " + resourceName);
                throw new RuntimeException ("Sorry! Unable to locate file on classpath: " + resourceName);
            }
            InputStream is = resourceUrl.openStream();
            out.println("Copying: " + resourceName + " to " + file + " is=" + is);

            File existingFile = new File(file);
            if (existingFile.exists())
                tellUser("Existing file: " + file);

            copy_stream_to_file(is, file);

            File newFile = new File(file);
            if (!newFile.exists()) {
                tellUser("Sorry! Unable to copy file: " + file);
                throw new RuntimeException ("Sorry! Unable to copy file: " + file);
            }

            out.println("Copied resource + " + resourceName + " with size " + newFile.length() + " bytes to " + newFile.getAbsolutePath());
        }
	}

	private static boolean isURLAlive(String url) throws IOException
	{
		try {
			// attempt to fetch the page
			// throws a connect exception if the server is not even running
			// so catch it and return false

			// since "index" may auto load default archive, attach it to session, and redirect to "info" page,
			// we need to maintain the session across the pages.
			// see "Maintaining the session" at http://stackoverflow.com/questions/2793150/how-to-use-java-net-urlconnection-to-fire-and-handle-http-requests
			CookieHandler.setDefault(new CookieManager(null, CookiePolicy.ACCEPT_ALL));

			out.println("Testing for already running Surf by probing " + url);
			HttpURLConnection u = (HttpURLConnection) new URL(url).openConnection();
			if (u.getResponseCode() == 200)
			{
				u.disconnect();
                out.println("SURF is already running!");
                return true;
			}
			u.disconnect();
		} catch (ConnectException ce) { }
        out.println("Good, Surf is not already running");
        return false;
	}

	private static boolean killRunningServer(String url) throws IOException
	{
		try {
			// attempt to fetch the page
			// throws a connect exception if the server is not even running
			// so catch it and return false
			// String http = url + "exit.jsp?message=Shutdown%20request%20from%20a%20different%20instance%20of%20ePADD"; // version num spaces and brackets screw up the URL connection

            // actually this should not be a HTTP connection, it can be simply any connection
			out.println ("Sending a kill request to " + url);
			HttpURLConnection u = (HttpURLConnection) new URL(url).openConnection();
			u.connect();
			if (u.getResponseCode() == 200)
			{
				u.disconnect();
				return true;
			}
			u.disconnect();
		} catch (ConnectException ce) {
            err.println ("Warning: unable to kill running server: " + ce);
        }
		return false;
	}

	/** waits till the page at the given url is alive, subject to timeout
	 * returns true if the page is alive.
	 * false if the page is not alive at the timeout
	 */

	private static boolean waitTillPageAlive(String url, int timeoutSecs) throws IOException
	{
		int tries = 0;
		int secsBetweenTries = 1;
		while (true)
		{
			boolean alive = isURLAlive(url);
			tries++;
			if (alive)
				break;

			out.println ("Web app not deployed after " + tries + " tries");

			try { Thread.sleep (secsBetweenTries * 1000); } catch (InterruptedException ie) { }
			if (tries * secsBetweenTries > timeoutSecs)
			{
				out.println ("\n\n\nSORRY! FAILED TO START CORRECTLY AFTER " + tries + " TRIES!\n\n\n");
				return false;
			}
		}
		out.println ("The Surf web application was deployed successfully (#tries: " + tries + ")");
		return true;
	}

	/** warns in a way that forces the user to notice (hopefully) */
 	public static void aggressiveWarn (String message, long sleepMillis)
 	{
 		out.println ("\n\n\n\n\n");
 		out.println("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
 		out.println ("\n\n\n\n\n\n" + message + "\n\n\n\n\n\n");
 		out.println("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
 	 	out.println ("\n\n\n\n\n");

 		if (sleepMillis > 0)
 			try { Thread.sleep (sleepMillis); } catch (Exception e) { }
 	}

	private static Options getOpt()
	{
		// create the Options
		// consider a local vs. global (hosted) switch. some settings will be disabled if its in global mode
		Options options = new Options();
		options.addOption( "h", "help", false, "print this message");
		options.addOption( "p", "port", true, "port number");
		options.addOption( "d", "debug", false, "turn debug messages on");
		options.addOption( "n", "no-browser-open", false, "no browser open");
	//	options.addOption( "ns", "no-shutdown", false, "no auto shutdown");
		return options;
	}

	private static void launchBrowser(String url) throws BrowserLaunchingInitializingException, UnsupportedOperatingSystemException, IOException, URISyntaxException
	{
		// we use the browser launcher only for windows to try and skip IE
    	if (System.getProperty("os.name").toLowerCase().indexOf("windows") >= 0)
    	{
    		launcher = new BrowserLauncher();
        	List<String> browsers = (List<String>) launcher.getBrowserList();
    		out.print ("The available browsers on this system are: ");
    		// the preferred browser is the first browser, that is not IE.
    		for (String str: browsers)
    		{
    			out.print (str + " ");
    			if (preferredBrowser == null && !"IE".equals(str))
    				preferredBrowser = str;
    		}
    		out.println();
    		launcher.setNewWindowPolicy(true); // force new window

            tellUser ("Launching URL in browser: " + url);

    		if (preferredBrowser != null)
    			launcher.openURLinBrowser(url);
    		else
    			launcher.openURLinBrowser(preferredBrowser, url);
    	}
        else
        {
        	out.println ("Using Java Desktop launcher to browse to " + url);
			if (desktop != null) // just defensive in case the desktop is not there (maybe in discovery mode?)
	        	desktop.browse(new URI(url));
        }
	}

    /** sets up SETTINGS_DIR by reading surf.properties in home dir if required, also sets up IS_DISCOVERY_MODE.
     * out and err streams are expected to be available.
     * call this before setting up logging because it can affect the location of the log file
     */
	private static void readConfigFile() {

	}

	/* sets out and err to point to the launcher log files (in the settings_dir) */
	private static void setupLoggingForLauncher()
	{
		String logFile = SETTINGS_DIR + File.separatorChar + "surf.log";
		String launcherLogFile = SETTINGS_DIR + File.separatorChar + "surf-launcher.log";
		String launcherLogFileErr = SETTINGS_DIR + File.separatorChar + "surf-launcher.err.log";
		new File(SETTINGS_DIR).mkdirs();

		System.out.println("Set logging to " + logFile);
		try {
			out = new PrintStream(new FileOutputStream(launcherLogFile), true /* autoFlush */, "UTF-8");
			err = new PrintStream(new FileOutputStream(launcherLogFileErr), true /* autoFlush */, "UTF-8");
		} catch (Exception e) {
			out = System.out;
			err = System.err; // reset them back
			System.err.println ("Error redirecting out and err streams to " + launcherLogFile);
			e.printStackTrace(System.err);
		}
	}
	
	private static void parseOptions(String[] args) throws ParseException
	{
	    // set javawebstart.version to a dummy value if not already set (might happen when running with java -jar from cmd line)
	    // exit.jsp doesn't allow us to showdown unless this prop is set
	    if (System.getProperty("javawebstart.version") == null)
		    System.setProperty("javawebstart.version", "UNKNOWN");
	    
	    if (args.length > 0)
		{
		    out.print (args.length + " argument(s): ");
		    for (int i = 0; i < args.length; i++)
        		out.print (args[i] + " ");
		    out.println();
		}
    	
	    Options options = getOpt();
	    CommandLineParser parser = new PosixParser();
	    CommandLine cmd = parser.parse(options, args);

	    if (cmd.hasOption("help"))
		{
		    HelpFormatter formatter = new HelpFormatter();
		    formatter.printHelp( "surf batch mode", options);
		    return;
		}
	    
	    debug = false;
	    if (cmd.hasOption("debug"))
		{
		    URL url = ClassLoader.getSystemResource("log4j.properties.debug");
		    out.println ("Loading logging configuration from url: " + url);
		    PropertyConfigurator.configure(url);
		    debug = true;
		} else if (cmd.hasOption("debug-address-book"))
		{
		    URL url = ClassLoader.getSystemResource("log4j.properties.debug.ab");
		    out.println ("Loading logging configuration from url: " + url);
		    PropertyConfigurator.configure(url);
		    debug = false;
		}
	    else if (cmd.hasOption("debug-groups"))
		{
		    URL url = ClassLoader.getSystemResource("log4j.properties.debug.groups");
		    out.println ("Loading logging configuration from url: " + url);
		    PropertyConfigurator.configure(url);
		    debug = false;
		}
	    
	    if (cmd.hasOption("no-browser-open") || System.getProperty("nobrowseropen") != null)
        	browserOpen = false;
	    
	    if (cmd.hasOption("port"))
		{
		    String portStr = cmd.getOptionValue('p');
		    try { 
    			PORT = Integer.parseInt (portStr); 
    			String mesg = " Running on port: " + PORT;
    			out.println (mesg);
		    } catch (NumberFormatException nfe) {
    			out.println ("invalid port number " + portStr);
		    }
		} 

	    /*
	      if (!cmd.hasOption("no-shutdown")) {
	      // arrange to kill Muse after a period of time, we don't want the server to run forever
	      
	      // i clearly have too much time on my hands right now...
	      long secs = KILL_AFTER_MILLIS/1000;
	      long hh = secs/3600;
	      long mm = (secs%3600)/60;
	      long ss = secs % (60);
	      out.print ("ePADD will shut down automatically after ");
	      if (hh != 0)
	      out.print (hh  + " hours ");
	      if (mm != 0 || (hh != 0 && ss != 0))
	      out.print (mm + " minutes");
	      if (ss != 0)
	      out.print (ss + " seconds");
	      out.println();
	      
	      Timer timer = new Timer(); 
	      TimerTask tt = new ShutdownTimerTask();
	      timer.schedule (tt, KILL_AFTER_MILLIS);
	      }
	    */
	    System.setSecurityManager(null); // this is important	
	}

	/** unpack files needed from epadd-standalone.jar (epadd.war, crossdomain.xml and index.html) into tmp directories and point the server to them */
	private static void setupTomcatServer() throws IOException, ServletException
	{
	    out.println("Setting up Surf at time " + formatDateLong(new GregorianCalendar()));
	    out.println("Setting up Tomcat");
	    String tmp = System.getProperty("java.io.tmpdir");

        String baseDir = tmp + File.separator + "surf" + File.separator; // everything we do will be under this directory... don't touch anything else

        // create webapp and work folders
        {
            // create the webapps dir under tmpdir, otherwise we see a disturbing error message, it creates a local tomcat.9099 dir for deployment etc.
         //    debugFile = tmp + File.separatorChar + "debug.txt";

            // important: need to first clear the webapps and work dirs, otherwise it sometimes picks up a previous version of the war
            String webappsDir = baseDir + "webapps" + File.separator;
            err.println("base dir: " + baseDir);
            err.println("tmp: " + tmp);
            err.println("webapps dir: " + webappsDir);

            File webappsDirFile = new File(webappsDir);
            if (webappsDirFile.exists()) {
                out.println("Clearing Tomcat webapps dir: " + webappsDir);
                FileUtils.deleteDirectory(webappsDirFile);
                out.println("Done Tomcat clearing webapps dir: " + webappsDir);
            }
            new File(webappsDir).mkdirs();
            out.println("Created webapps folder at " + webappsDir);

            String workDir = baseDir + "work" + File.separator;
            File workDirFile = new File(workDir);
            if (workDirFile.exists()) {
                out.println("Clearing Tomcat work dir: " + workDir);
                FileUtils.deleteDirectory(workDirFile);
                out.println("Done clearing Tomcat work dir: " + workDir);
            }
            new File(workDir).mkdirs();
            out.println("Created work folder at " + workDir);
        }
        server = new Tomcat();
        server.setPort(PORT);
        server.setBaseDir(baseDir);
        out.println ("Server basedir set to " + baseDir);

        SURF_WEBAPP = null;

        // deploy crossdomain.xml and index.html and surf.war at their respective paths in the server
        try {

            // context for /surf
            copyResource("surf.war", baseDir); // extract from standalone.jar to baseDir
            SURF_WEBAPP = server.addWebapp("/" + WEBAPP_NAME, baseDir + "surf.war"); // deploy basedir/webapp.war to /webapp in tomcat
//			copyResource ("web.xml", baseDir);
//			SURF_WEBAPP.setAltDDName(baseDir + File.separator + "web.xml");
        } catch (Exception e) {
            out.println("Could not copy resources to deploy Surf! " + e);
        }

        if (SURF_WEBAPP == null) {
            out.println("Aborting... no surf webapp");
        }
        else {
            out.println("Deployed webapp to /" + WEBAPP_NAME);
            out.println("Surf running check URL is " + APP_CHECK_URL);
        }

	    // Note: touch the connector only after setting the base dir and other setting params above, else the none of the settings would work
	    // On Terry's archive, to update the correspondents list, we need a cushion of 10MB post size
	    server.getConnector().setMaxPostSize(10000000);
	}
	
	private static void shutdownSessions() {
		out.println ("shutting down sessions...");
		Session[] sessions = SURF_WEBAPP.getManager().findSessions();
		for (Session session: sessions)
		    {
			if (session instanceof HttpSession)
			    try {
        			((HttpSession) session).invalidate();
        			out.println ("Successfully shut down session: " + session);
			    } catch (Exception e) {
        			out.println ("Exception " + e + " while shutting down session " + session);
			    }
		    }
	}


	/** we need a system tray icon for management.
	 * http://docs.oracle.com/javase/6/docs/api/java/awt/SystemTray.html */
	private static void setupSystemTrayIcon()
	{
		// Set the app name in the menu bar for mac. 
		// c.f. http://stackoverflow.com/questions/8918826/java-os-x-lion-set-application-name-doesnt-work
		System.setProperty("apple.laf.useScreenMenuBar", "true");
		System.setProperty("com.apple.mrj.application.apple.menu.about.name", "Surf");
		try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception e) { throw new RuntimeException(e); }

	    TrayIcon trayIcon = null;
	     if (SystemTray.isSupported()) 
	     {
		     tellUser ("Adding Surf to the system tray");
	         SystemTray tray = SystemTray.getSystemTray();
	         
	         URL u = in.edu.ashoka.surf.launcher.TomcatMain.class.getClassLoader().getResource("muse-icon.png"); // note: this better be 16x16, Windows doesn't resize! Mac os does.
	         out.println ("Surf icon resource is " + u);

	         Image image = Toolkit.getDefaultToolkit().getImage(u);
	         out.println ("Image = " + image);
	         
	         // create menu items and their listeners
	         ActionListener openMuseControlsListener = new ActionListener() {
	             public void actionPerformed(ActionEvent e) {
	            	 try {
						launchBrowser(BASE_URL); // no + "info" for Surf like in muse
					} catch (Exception e1) {
						e1.printStackTrace();
					}
	             }
	         };
	         
	         ActionListener QuitMuseListener = new ActionListener() {
	             public void actionPerformed(ActionEvent e) {
			        out.println("*** Received quit from system tray. Stopping the Tomcat embedded web server.");
	 	            try { shutdownSessions(); server.stop(); } catch(Exception ex) { throw new RuntimeException(ex); }
		            System.exit(0); // we need to explicitly system.exit because we now use Swing (due to system tray, etc).
	             }
	         };

	         // create a popup menu
	         PopupMenu popup = new PopupMenu();
	         MenuItem defaultItem = new MenuItem("Open Surf window");
	         defaultItem.addActionListener(openMuseControlsListener);
	         popup.add(defaultItem);
	         MenuItem quitItem = new MenuItem("Quit Surf");
	         quitItem.addActionListener(QuitMuseListener);
	         popup.add(quitItem);
	         
	         /// ... add other items
	         // construct a TrayIcon
            String message = "Surf menu";
            // on windows - the tray menu is a little non-intuitive, needs a right click (plain click seems unused)
            if (System.getProperty("os.name").toLowerCase().indexOf("windows") >= 0)
                message = "Right click for Surf menu";
	         trayIcon = new TrayIcon(image, message, popup);
            System.out.println ("tray Icon = " + trayIcon);
	         // set the TrayIcon properties
//	         trayIcon.addActionListener(openMuseControlsListener);
	         try {
	             tray.add(trayIcon);
	         } catch (AWTException e) {
	             out.println(e);
	         }
	         // ...
	     } else {
	         // disable tray option in your application or
	         // perform other actions
//	         ...
	     }
	     System.out.println("Done!");
	     // ...
	     // some time later
	     // the application state has changed - update the image
	     if (trayIcon != null) {
	 //        trayIcon.setImage(updatedImage);
	     }
	     // ...
	}

	public static String getMemoryStats()
	{
		Runtime r = Runtime.getRuntime();
		System.gc();
		return r.freeMemory()/MB + " MB free, " + (r.totalMemory()/MB - r.freeMemory()/MB) + " MB used, "+ r.maxMemory()/MB + " MB max, " + r.totalMemory()/MB + " MB total";
	}

	public static String formatDateLong(Calendar d)
	{
		if (d == null)
			return "??-??";
		else
			return d.get(Calendar.YEAR) + "-" + String.format("%02d", (1+d.get(Calendar.MONTH))) + "-" + String.format ("%02d", d.get(Calendar.DAY_OF_MONTH)) + " "
					+ String.format("%02d", d.get(Calendar.HOUR_OF_DAY)) + ":" + String.format("%02d", d.get(Calendar.MINUTE)) + ":" +
					String.format("%02d", d.get(Calendar.SECOND));
	}

	// util methods
	public static void copy_stream_to_file(InputStream is, String filename) throws IOException
	{
		int bufsize = 64 * 1024;
		BufferedInputStream bis = null;
		BufferedOutputStream bos = null;
		try {
			File f = new File(filename);
			if (f.exists())
			{
				// out.println ("File " + filename + " exists");
				boolean b = f.delete(); // best effort to delete file if it exists. this is because windows often complains about perms
				if (!b)
					out.println ("Warning: failed to delete " + filename);
			}
			bis = new BufferedInputStream(is, bufsize);
			bos = new BufferedOutputStream(new FileOutputStream(filename), bufsize);
			byte buf[] = new byte[bufsize];
			while (true)
			{
				int n = bis.read(buf);
				if (n <= 0)
					break;
				bos.write (buf, 0, n);
			}
		} catch (IOException ioe)
		{
			out.println ("ERROR trying to copy data to file: " + filename + ", forging ahead nevertheless");
		} finally {
			if (bis != null) bis.close();
			if (bos != null) bos.close();
		}
	}

	/** this is a stop tomcat thread to listen to a message on some port -- any message on this port will shut down Muse.
 * mainly meant so that a new launch of Muse will kill the previously running version. */
static class ShutdownThread extends Thread {
	static PrintStream out = System.out;

    private ServerSocket socket;
    private int shutdownPort;
    private Tomcat server;
    public ShutdownThread(Tomcat server, int shutdownPort) {
    	this.server = server;
    	this.shutdownPort = shutdownPort;
        setDaemon(false); // deliberately make it non-daemon so as to keep this program running.
        setName("Surf shutdown thread");
        try {
            socket = new ServerSocket(this.shutdownPort, 1, InetAddress.getByName("127.0.0.1"));
        } catch(Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void run() {
        out.println("*** running tomcat 'stop' thread");
        Socket accept;
        try {
            accept = socket.accept();
            BufferedReader reader = new BufferedReader(new InputStreamReader(accept.getInputStream()));
            // wait for a readline
            String line = reader.readLine();
            out.println("Received the line: " + line);

            // any input received, stop the server
            shutdownSessions();
			if (server != null)
	            server.stop();
            out.println("*** Stopped the Tomcat embedded web server. received: " + line);
            accept.close();
            socket.close();
            System.exit(1); // we need to explicitly system.exit because we now use Swing (due to system tray, etc).
        } catch(Exception e) {
            out.println ("Error trying to shutdown Tomcat embedded web server gracefully: " + e);
            e.printStackTrace (out);
            throw new RuntimeException(e);
        }
    }
}

    private static void killExistingSurfIfRunning(String baseUrl) {
        APP_CHECK_URL = baseUrl + "js/jquery-1.12.1.min.js"; // for quick check of existing muse or successful start up. baseUrl may take some time to run and may not always be available now that we set dirAllowed to false and public mode does not serve /muse.

        // handle frequent error of user trying to launch another server when its already on
        // server.start() usually takes a few seconds to return
        // after that it takes a few seconds for the webapp to deploy
        // ignore any exceptions along the way and assume not if we can't prove it is alive
        boolean urlAlive = false;
        try { urlAlive = isURLAlive(APP_CHECK_URL); }
        catch (Exception e) { out.println ("Exception: e"); e.printStackTrace(out); }

        if (!urlAlive)
            return; // nothing to do

        // oh, no... messy job of cleaning up the prev. running surf.
        // try to do it by sending it a shutdown message

        {
            tellUser ("Surf already running at: " + baseUrl + ", will shut it down!");
            try { killRunningServer("http://localhost:" + (PORT+1) + "/"); } catch (IOException ioe) { err.println ("Error trying to kill running Surf! " + ioe); }

            int N_KILL_TRIES = 20, N_KILL_PERIOD_MILLIS = 3000;
            // check every 3 secs 20 times (total 1 minute) for the previous surf to die
            // if it doesn't die after a minute, fail
            for (int i = 0; i < N_KILL_TRIES; i++)
            {
                try { Thread.sleep(N_KILL_PERIOD_MILLIS);} catch (InterruptedException ie) {}
                tellUser ("Checking " + APP_CHECK_URL);
                try {urlAlive = isURLAlive(APP_CHECK_URL);}
                catch (Exception e)
                {
                    out.println("Exception: " + e);
                    e.printStackTrace(out);
                }
                if (!urlAlive)
                    break;
            }

            if (!urlAlive)
            {
                tellUser ("Good. Shutdown succeeded, we'll restart Surf.");
            } else
            {
                String message = "Previously running Surf still alive despite attempt to shut it down, disabling fresh restart!\n";
                message += "If you just want to use the previous instance of Surf, please go to " + baseUrl;
                message += "\nTo kill this instance, please go to your computer's task manager and kill running java or javaw processes.\nThen try launching Surf again.\n";
                aggressiveWarn(message, 2000);
                tellUser ("Sorry, unable to kill previous Surf. Quitting!");
                try { Thread.sleep (10000); } catch (InterruptedException ie) { }
                if (SPLASH != null)
                    SPLASH.close();
                return;
            }
        }

    }

	public static void main (String args[]) throws Exception
	{
        if (args.length > 0 && "--test".equals (args[0])) {
            try {
                //Tester.main(args);
            } catch (Exception e) {
                err.println ("Exception running self-test: " + e);
                System.exit (1);
            }
            System.exit (0);
        }

		readConfigFile(); // should be read first because it reads Surf mode etc.
		// IS_DISCOVERY_MODE should be set up here

		tellUser ("Setting up logging...");

		setupLoggingForLauncher();

		parseOptions(args);

		BASE_URL = "http://localhost:" + PORT + "/" + WEBAPP_NAME + "/";

//		tellUser (splash, "Log file: " + debugFile + "***\n");

		out.println ("Starting up Surf on the local computer at " + BASE_URL + ", " + formatDateLong(new GregorianCalendar()));
	//	out.println ("***For troubleshooting information, see this file: " + debugFile + "***\n");
		out.println ("Current directory = " + System.getProperty("user.dir") + ", home directory = " + System.getProperty("user.home"));
		out.println("Memory status at the beginning: " + getMemoryStats());

//		if (Runtime.getRuntime().maxMemory()/MB < 512) // user probably forgot to run with -Xmx
//			aggressiveWarn ("You are probably running Surf without enough memory. \nIf you launched ePADD from the command line, you can increase memory with an option like java -Xmx4g", 2000);

		tellUser ("Memory: " + getMemoryStats());

        killExistingSurfIfRunning(BASE_URL);

        tellUser ("Starting Surf at " + BASE_URL);

		setupTomcatServer();
		server.start();

        /*
		PrintStream debugOut1 = err;
		try {
			File f = new File(debugFile);
			if (f.exists())
				f.delete(); // particular problem on windows :-(
			debugOut1 = new PrintStream(new FileOutputStream(debugFile), false, "UTF-8");
		} catch (IOException ioe) {
			out.println ("Warning: failed to delete debug file " + debugFile + " : " + ioe);
		}

		final PrintStream debugOut = debugOut1;
*/


		boolean success = waitTillPageAlive(APP_CHECK_URL, TIMEOUT_SECS);

		if (success)
		{
			try {
                // set it up so as to save sessions before exiting
                Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                    public void run() {
                        try {
                            shutdownSessions();
                            server.stop();
                            server.destroy();
//					debugOut.close();
                        } catch (Exception e) {
                            // TODO Auto-generated catch block
                            e.printStackTrace();
                        }
                    }
                }));

                // start a listener for the shutdown port
				int shutdownPort = PORT + 1; // shut down port is arbitrarily set to PORT+1. it is ASSUMED to be free.
				new ShutdownThread(server, shutdownPort).start(); // this will start a non-daemon thread that keeps the process alive
				tellUser ("Listening for Surf shutdown message on port " + shutdownPort);

			} catch (Exception e) {
				out.println ("Unable to start shutdown listener, you will have to stop the server manually using Cmd-Q on Mac OS or kill javaw processes on Windows");
			}

			try { setupSystemTrayIcon(); }
			catch (Exception e) { out.println ("Unable to setup system tray icon: " + e); e.printStackTrace(err); }

			// open browser window
			if (browserOpen)
			{
				preferredBrowser = null;
				// launch a browser here
				try {
					launchBrowser(BASE_URL);
				} catch (Exception e) {
					out.println ("Warning: Unable to launch browser due to exception (use the -n option to prevent Surf from trying to launch a browser):");
					e.printStackTrace(out);
				}
			}
		}
		else
		{
			tellUser ("SORRY!!! UNABLE TO START SURF, EXITING");
		}

		if (SPLASH != null)
            SPLASH.close();
		// program should not halt here, as ShutdownThread is running as a non-daemon thread.
		// splashDemo.closeWindow();
	}

}
