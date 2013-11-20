package ca.mcgill.mcb.pcingola.bigDataScript;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Properties;

import ca.mcgill.mcb.pcingola.bigDataScript.util.Gpr;
import ca.mcgill.mcb.pcingola.bigDataScript.util.Timer;

/**
 * Config file
 * 
 * @author pcingola

 */
public class Config {

	public static final String DEFAULT_CONFIG_DIR = Gpr.HOME + "/.bds";
	public static final String DEFAULT_CONFIG_FILE = DEFAULT_CONFIG_DIR + "/" + BigDataScript.class.getSimpleName().toLowerCase() + ".config";
	public static final String DEFAULT_INCLUDE_DIR = DEFAULT_CONFIG_DIR + "/include";

	public static final String BDS_INCLUDE_PATH = "BDS_PATH"; // BDS include path (colon separated list of directories to look for include files)

	private static Config configInstance = null; // Config is some kind of singleton because we want to make it accessible from everywhere

	public static Config get() {
		if (configInstance == null) configInstance = new Config();
		return configInstance;
	}

	boolean debug = false; // Debug mode?
	boolean verbose = false; // Verbose mode?
	boolean log = false; // Log all commands?
	String configDirName;
	String pidFile;
	Properties properties;
	ArrayList<String> sshNodes;

	ArrayList<String> includePath;

	public Config() {
		read(DEFAULT_CONFIG_FILE); // Read config file 
		configInstance = this;
	}

	/**
	 * Create a configuration from 'configFileName'
	 * @param genomeVersion
	 * @param configFileName
	 */
	public Config(String configFileName) {
		read(configFileName); // Read config file 
		configInstance = this;
	}

	/**
	 * Get a property as a double
	 * @param propertyName
	 * @return
	 */
	protected double getDouble(String propertyName, double defaultValue) {
		String val = getString(propertyName);
		if (val == null) return defaultValue;
		return Gpr.parseDoubleSafe(val);
	}

	/**
	 * A collection of strings showing where to search for include files
	 * 
	 * TODO: Add path from config file
	 * TODO: Add default system-wide include path ("/usr/local/bds/include")
	 * 
	 * @return
	 */
	public Collection<String> getIncludePath() {
		// Create array if needed
		if (includePath == null) {
			includePath = new ArrayList<String>();

			// Add by search order
			includePath.add("."); // Current dir (obviously)
			includePath.add(DEFAULT_INCLUDE_DIR); // Default include path ($HOME/.bds/include)

			// Add BDS_PATH environment variable
			String bdsPath = System.getenv(BDS_INCLUDE_PATH);
			if ((bdsPath != null) && !bdsPath.isEmpty()) {
				String incPaths[] = bdsPath.split(":");
				for (String incPath : incPaths) {
					if (!incPath.isEmpty()) includePath.add(incPath);
				}
			}
		}

		return includePath;
	}

	/**
	 * Get a property as a long
	 * @param propertyName
	 * @return
	 */
	protected long getLong(String propertyName, long defaultValue) {
		String val = getString(propertyName);
		if (val == null) return defaultValue;
		return Gpr.parseLongSafe(val);
	}

	public String getPidFile() {
		return pidFile;
	}

	public ArrayList<String> getSshNodes() {
		return sshNodes;
	}

	/**
	 * Get a property as a string
	 * @param propertyName
	 * @return
	 */
	protected String getString(String propertyName) {
		return properties.getProperty(propertyName);
	}

	public boolean isDebug() {
		return debug;
	}

	public boolean isLog() {
		return log;
	}

	public boolean isVerbose() {
		return verbose;
	}

	/**
	 * Read configuration file and create all 'genomes' 
	 * @return
	 */
	private void read(String configFileName) {

		if (!Gpr.exists(configFileName)) {
			if (verbose) Timer.showStdErr("Config file '" + configFileName + "' not found");
			return;
		}

		//---
		// Read properties file
		//---
		properties = new Properties();
		try {
			properties.load(new FileReader(new File(configFileName)));
		} catch (FileNotFoundException e) {
			throw new RuntimeException("Cannot find config file '" + configFileName + "'");
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		// Config directory
		configDirName = "";
		try {
			File configDir = new File(configFileName).getAbsoluteFile().getParentFile();
			configDirName = configDir.getCanonicalPath();
		} catch (IOException e1) {
		}

		//---
		// Set attributes
		//---

		// Set properties
		setFromProperties();
	}

	public void setDebug(boolean debug) {
		this.debug = debug;
	}

	/**
	 * Set from parameter properties
	 */
	void setFromProperties() {
		//---
		// Parse Ssh nodes option
		//---
		sshNodes = new ArrayList<String>();
		String sshNodesStr = getString("ssh.nodes");
		if (sshNodesStr != null) {
			for (String sshNode : sshNodesStr.split(",")) {
				sshNode = sshNode.trim();
				if (!sshNode.isEmpty()) sshNodes.add(sshNode);
			}
		}
	}

	public void setLog(boolean log) {
		this.log = log;
	}

	public void setPidFile(String pidFile) {
		this.pidFile = pidFile;
	}

	public void setVerbose(boolean verbose) {
		this.verbose = verbose;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();

		return sb.toString();
	}
}