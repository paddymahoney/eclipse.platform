/*******************************************************************************
 * Copyright (c) 2000, 2004 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.update.internal.configurator;

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.util.*;

import javax.xml.parsers.*;
import javax.xml.transform.*;
import javax.xml.transform.dom.*;
import javax.xml.transform.stream.*;

import org.eclipse.core.internal.boot.*;
import org.eclipse.core.runtime.*;
import org.eclipse.osgi.service.datalocation.*;
import org.eclipse.update.configurator.*;
import org.w3c.dom.*;

/**
 * This class is responsible for providing the features and plugins (bundles) to 
 * the runtime. Configuration data is stored in the configuration/org.eclipse.update/platform.xml file.
 * When eclipse starts, it tries to load the config info from platform.xml.
 * If the file does not exist, then it also tries to read it from a temp or backup file.
 * If this does not succeed, a platform.xml is created by inspecting the eclipse 
 * installation directory (its features and plugin folders).
 * If platform.xml already exists, a check is made to see when it was last modified
 * and whether there are any file system changes that are newer (users may manually unzip 
 * features and plugins). In this case, the newly added features and plugins are picked up.
 * A check for existence of features and plugins is also performed, to detect deletions.
 */
public class PlatformConfiguration implements IPlatformConfiguration, IConfigurationConstants {

	private static PlatformConfiguration currentPlatformConfiguration = null;
	private static final DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
	private static final TransformerFactory transformerFactory = TransformerFactory.newInstance();

	private Configuration config;
	private URL configLocation;
	private HashMap externalLinkSites; // used to restore prior link site state
	private long changeStamp;
	private long featuresChangeStamp;
	private boolean featuresChangeStampIsValid;
	private long pluginsChangeStamp;
	private boolean pluginsChangeStampIsValid;
	private File cfgLockFile;
	private RandomAccessFile cfgLockFileRAF;

	private static final String ECLIPSE = "eclipse"; //$NON-NLS-1$
	private static final String CONFIG_HISTORY = "history"; //$NON-NLS-1$
	private static final String CONFIG_NAME = "platform.xml"; //$NON-NLS-1$
	private static final String CONFIG_FILE_INIT = "install.ini"; //$NON-NLS-1$
	private static final String CONFIG_INI = "config.ini"; //NON-NLS-1$
	private static final String CONFIG_FILE_LOCK_SUFFIX = ".lock"; //$NON-NLS-1$
	private static final String CONFIG_FILE_TEMP_SUFFIX = ".tmp"; //$NON-NLS-1$
	private static final String CONFIG_FILE_BAK_SUFFIX = ".bak"; //$NON-NLS-1$
	private static final String CHANGES_MARKER = ".newupdates"; //$NON-NLS-1$
	private static final String LINKS = "links"; //$NON-NLS-1$
	private static final String[] BOOTSTRAP_PLUGINS = {}; //$NON-NLS-1$

	private static final String INIT_DEFAULT_FEATURE_ID = "feature.default.id"; //$NON-NLS-1$
	private static final String INIT_DEFAULT_PLUGIN_ID = "feature.default.plugin.id"; //$NON-NLS-1$
	private static final String INIT_DEFAULT_FEATURE_APPLICATION = "feature.default.application"; //$NON-NLS-1$
	private static final String DEFAULT_FEATURE_ID = "org.eclipse.platform"; //$NON-NLS-1$
	private static final String DEFAULT_FEATURE_APPLICATION = "org.eclipse.ui.ide.workbench"; //$NON-NLS-1$

	private static final String LINK_PATH = "path"; //$NON-NLS-1$
	private static final String LINK_READ = "r"; //$NON-NLS-1$
	private static final String LINK_READ_WRITE = "rw"; //$NON-NLS-1$
	private static URL installURL;
	
	private PlatformConfiguration(Location platformConfigLocation) throws CoreException, IOException {

		this.externalLinkSites = new HashMap();
		this.config = null;
		
		// initialize configuration
		initializeCurrent(platformConfigLocation);

		// Detect external links. These are "soft link" to additional sites. The link
		// files are usually provided by external installation programs. They are located
		// relative to this configuration URL.
		configureExternalLinks();

		// pick up any first-time default settings (relative to install location)
		loadInitializationAttributes();

		// Validate sites in the configuration. Causes any sites that do not exist to
		// be removed from the configuration
		validateSites();

		// compute differences between configuration and actual content of the sites
		// (base sites and link sites)
		// Note: when the config is transient (generated by PDE, etc.) we don't reconcile
		changeStamp = computeChangeStamp();
		if (changeStamp > config.getDate().getTime() && !isTransient())
			reconcile();

//		// save configuration if there were changes
//		if (config.isDirty())
//			save();
		
		// determine which plugins we will use to start the rest of the "kernel"
		// (need to get core.runtime matching the executing core.boot and
		// xerces matching the selected core.runtime)
		//		locateDefaultPlugins();
	}

	PlatformConfiguration(URL url) throws Exception {
		this.externalLinkSites = new HashMap();
		initialize(url);
	}

	/*
	 * @see IPlatformConfiguration#createSiteEntry(URL, ISitePolicy)
	 */
	public ISiteEntry createSiteEntry(URL url, ISitePolicy policy) {
		return new SiteEntry(url, policy);
	}

	/*
	 * @see IPlatformConfiguration#createSitePolicy(int, String[])
	 */
	public ISitePolicy createSitePolicy(int type, String[] list) {
		return new SitePolicy(type, list);
	}

	/*
	 * @see IPlatformConfiguration#createFeatureEntry(String, String, String, boolean, String, URL)
	 */
	public IFeatureEntry createFeatureEntry(String id, String version, String pluginVersion, boolean primary, String application, URL[] root) {
		return new FeatureEntry(id, version, pluginVersion, primary, application, root);
	}

	/*
	 * @see IPlatformConfiguration#createFeatureEntry(String, String, String,
	 * String, boolean, String, URL)
	 */
	public IFeatureEntry createFeatureEntry(String id, String version, String pluginIdentifier, String pluginVersion, boolean primary, String application, URL[] root) {
		return new FeatureEntry(id, version, pluginIdentifier, pluginVersion, primary, application, root);
	}

	/*
	 * @see IPlatformConfiguration#configureSite(ISiteEntry)
	 */
	public void configureSite(ISiteEntry entry) {
		configureSite(entry, false);
	}

	/*
	 * @see IPlatformConfiguration#configureSite(ISiteEntry, boolean)
	 */
	public synchronized void configureSite(ISiteEntry entry, boolean replace) {

		if (entry == null)
			return;
	
		URL url = entry.getURL();
		if (url == null)
			return;

		String key = url.toExternalForm();
		if (config.getSiteEntry(key) != null && !replace)
			return;
	
		if (entry instanceof SiteEntry)
			config.addSiteEntry(key, (SiteEntry)entry);
	}

	/*
	 * @see IPlatformConfiguration#unconfigureSite(ISiteEntry)
	 */
	public synchronized void unconfigureSite(ISiteEntry entry) {
		if (entry == null)
			return;

		URL url = entry.getURL();
		if (url == null)
			return;
		
		String key = url.toExternalForm();	
		if (entry instanceof SiteEntry)
			config.removeSiteEntry(key);
	}

	/*
	 * @see IPlatformConfiguration#getConfiguredSites()
	 */
	public ISiteEntry[] getConfiguredSites() {
		if (config == null)
			return new ISiteEntry[0];
		
		SiteEntry[] sites = config.getSites();
		ArrayList enabledSites = new ArrayList(sites.length);
		for (int i=0; i<sites.length; i++) {
			if (sites[i].isEnabled())
				enabledSites.add(sites[i]);
		}
		return (ISiteEntry[])enabledSites.toArray(new ISiteEntry[enabledSites.size()]);
	}

	/*
	 * @see IPlatformConfiguration#findConfiguredSite(URL)
	 */
	public ISiteEntry findConfiguredSite(URL url) {
		return findConfiguredSite(url, true);
	}
	
	/**
	 * 
	 * @param url site url
	 * @param checkPlatformURL if true, check for url format that is platform:/...
	 * @return
	 */
	public SiteEntry findConfiguredSite(URL url, boolean checkPlatformURL) {
		if (url == null)
			return null;
		String key = url.toExternalForm();

		SiteEntry result = config.getSiteEntry(key);
		try {
			key = URLDecoder.decode(key, "UTF-8");
		} catch (UnsupportedEncodingException e) {
			// ignore
		}
		
		if (result == null) // retry with decoded URL string
			result = config.getSiteEntry(key);
			
		if (result == null && checkPlatformURL) {
			try {
				result = findConfiguredSite(Utils.asPlatformURL(url), false);
			} catch (Exception e) {
				//ignore
			}
		}
		return result;
	}

	/*
	 * @see IPlatformConfiguration#configureFeatureEntry(IFeatureEntry)
	 */
	public synchronized void configureFeatureEntry(IFeatureEntry entry) {
		if (entry == null)
			return;

		String key = entry.getFeatureIdentifier();
		if (key == null)
			return;

		// we should check each site and find where the feature is
		// located and then configure it
		if (config == null)
			config = new Configuration();

		SiteEntry defaultSite = config.getSiteEntry(PlatformURLHandler.PROTOCOL + PlatformURLHandler.PROTOCOL_SEPARATOR + "/" + "base" + "/");
		if (defaultSite != null) {
			defaultSite.addFeatureEntry(entry);
		}
		// else, do nothing (we need a site)
	}

	/*
	 * @see IPlatformConfiguration#unconfigureFeatureEntry(IFeatureEntry)
	 */
	public synchronized void unconfigureFeatureEntry(IFeatureEntry entry) {
		if (entry == null)
			return;

		String key = entry.getFeatureIdentifier();
		if (key == null)
			return;

		config.unconfigureFeatureEntry(entry);
	}

	/*
	 * @see IPlatformConfiguration#getConfiguredFeatureEntries()
	 */
	public IFeatureEntry[] getConfiguredFeatureEntries() {
		ArrayList configFeatures = new ArrayList();
		SiteEntry[] sites = config.getSites();
		for (int i=0; i<sites.length; i++) {
			FeatureEntry[] features = sites[i].getFeatureEntries();
			for (int j=0; j<features.length; j++)
				configFeatures.add(features[j]);
		}
		return (IFeatureEntry[])configFeatures.toArray(new IFeatureEntry[configFeatures.size()]);
	}

	/*
	 * @see IPlatformConfiguration#findConfiguredFeatureEntry(String)
	 */
	public IFeatureEntry findConfiguredFeatureEntry(String id) {
		if (id == null)
			return null;

		SiteEntry[] sites = config.getSites();
		for (int i=0; i<sites.length; i++) {
			FeatureEntry f = sites[i].getFeatureEntry(id);
			if (f != null)
				return f;
		}
		return null;
	}

	/*
	 * @see IPlatformConfiguration#getConfigurationLocation()
	 */
	public URL getConfigurationLocation() {
		return configLocation;
	}

	/*
	 * @see IPlatformConfiguration#getChangeStamp()
	 */
	public long getChangeStamp() {
		return config.getDate().getTime();
	}

	/*
	 * @see IPlatformConfiguration#getFeaturesChangeStamp()
	 */
	public long getFeaturesChangeStamp() {
		return 0;
	}

	/*
	 * @see IPlatformConfiguration#getPluginsChangeStamp()
	 */
	public long getPluginsChangeStamp() {
		return 0;
	}


	public String getApplicationIdentifier() {
		String feature = config.getDefaultFeature();

		// lookup application for feature (specified or defaulted)
		if (feature != null) {
			IFeatureEntry fe = findConfiguredFeatureEntry(feature);
			if (fe != null) {
				if (fe.getFeatureApplication() != null)
					return fe.getFeatureApplication();
			}
		}

		// return hardcoded default if we failed
		return DEFAULT_FEATURE_APPLICATION;
	}

	/*
	 * @see IPlatformConfiguration#getPrimaryFeatureIdentifier()
	 */
	public String getPrimaryFeatureIdentifier() {
		String primaryFeatureId = null;
		if (config.getDefaultFeature() != null)
			primaryFeatureId = config.getDefaultFeature(); // return customized default if set
		else
			primaryFeatureId = DEFAULT_FEATURE_ID; // return hardcoded default
		
		// check if feature exists
		if (findConfiguredFeatureEntry(primaryFeatureId) == null)
			return null;
		else
			return primaryFeatureId;
	}

	/*
	 * @see IPlatformConfiguration#getPluginPath()
	 */
	public URL[] getPluginPath() {
		ArrayList path = new ArrayList();
		Utils.debug("computed plug-in path:"); //$NON-NLS-1$

		ISiteEntry[] sites = getConfiguredSites();
		URL pathURL;
		for (int i = 0; i < sites.length; i++) {
			String[] plugins = sites[i].getPlugins();
			for (int j = 0; j < plugins.length; j++) {
				try {
					pathURL = new URL(((SiteEntry) sites[i]).getResolvedURL(), plugins[j]);
					path.add(pathURL);
					Utils.debug("   " + pathURL.toString()); //$NON-NLS-1$
				} catch (MalformedURLException e) {
					// skip entry ...
					Utils.debug("   bad URL: " + e); //$NON-NLS-1$
				}
			}
		}
		return (URL[]) path.toArray(new URL[0]);
	}

	/*
	 * @see IPlatformConfiguration#getBootstrapPluginIdentifiers()
	 */
	public String[] getBootstrapPluginIdentifiers() {
		return BOOTSTRAP_PLUGINS;
	}

	/*
	 * @see IPlatformConfiguration#setBootstrapPluginLocation(String, URL)
	 */
	public void setBootstrapPluginLocation(String id, URL location) {
	}

	/*
	 * @see IPlatformConfiguration#isUpdateable()
	 */
	public boolean isUpdateable() {
		return true;
	}

	/*
	 * @see IPlatformConfiguration#isTransient()
	 */
	public boolean isTransient() {
		if (config != null)
			return config.isTransient();
		else
			return false;
	}

	/*
	 * @see IPlatformConfiguration#isTransient(boolean)
	 */
	public void isTransient(boolean value) {
		if (this != getCurrent() && config != null)
			config.setTransient(value);
	}

	/*
	 * @see IPlatformConfiguration#refresh()
	 */
	public synchronized void refresh() {
		// Reset computed values. Will be lazily refreshed
		// on next access
		ISiteEntry[] sites = getConfiguredSites();
		for (int i = 0; i < sites.length; i++) {
			// reset site entry
			 ((SiteEntry) sites[i]).refresh();
		}
	}

	/*
	 * @see IPlatformConfiguration#save()
	 */
	public void save() throws IOException {
		if (isUpdateable())
			save(configLocation);
	}

	/*
	 * @see IPlatformConfiguration#save(URL)
	 */
	public synchronized void save(URL url) throws IOException {
		if (url == null)
			throw new IOException(Messages.getString("cfig.unableToSave.noURL")); //$NON-NLS-1$

		OutputStream os = null;
		if (!url.getProtocol().equals("file")) { //$NON-NLS-1$
			// not a file protocol - attempt to save to the URL
			URLConnection uc = url.openConnection();
			uc.setDoOutput(true);
			os = uc.getOutputStream();
			try {
				saveAsXML(os);
				config.setDirty(false);
			} catch (CoreException e) {
				throw new IOException(Messages.getString("cfig.unableToSave", url.toExternalForm())); //$NON-NLS-1$
			} finally {
				os.close();
			}
		} else {
			// file protocol - do safe i/o
			File cfigFile = new File(url.getFile().replace('/', File.separatorChar));
			if (!cfigFile.getName().equals(CONFIG_NAME))
				cfigFile = new File(cfigFile, CONFIG_NAME);
			File cfigDir = cfigFile.getParentFile();
			if (cfigDir != null)
				cfigDir.mkdirs();

			// Backup old file
			File oldConfigFile = new File(cfigDir, CONFIG_NAME);
			if (oldConfigFile.exists()){
				File backupDir = new File(cfigDir, CONFIG_HISTORY);
				if (!backupDir.exists())
					backupDir.mkdir();
				File preservedFile = new File(backupDir, String.valueOf(oldConfigFile.lastModified())+".xml");
				copy(oldConfigFile, preservedFile);
				preservedFile.setLastModified(oldConfigFile.lastModified());
			}
			
			// If config.ini does not exist, generate it
			writeConfigIni(cfigDir);
			
			// first save the file as temp
			File cfigTmp = new File(cfigFile.getAbsolutePath() + CONFIG_FILE_TEMP_SUFFIX);
			os = new FileOutputStream(cfigTmp);
			try {
				saveAsXML(os);
				// set file time stamp to match that of the config element
				cfigTmp.setLastModified(config.getDate().getTime());
				// make the change stamp to be the same as the config file
				changeStamp = config.getDate().getTime();
//				changeStampIsValid = true;
			} catch (CoreException e) {
				throw new IOException(Messages.getString("cfig.unableToSave", cfigTmp.getAbsolutePath())); //$NON-NLS-1$
			} finally {
				os.close();
			}

			// make the saved config the "active" one
			File cfigBak = new File(cfigFile.getAbsolutePath() + CONFIG_FILE_BAK_SUFFIX);
			cfigBak.delete(); // may have old .bak due to prior failure

			if (cfigFile.exists())
				cfigFile.renameTo(cfigBak);

			// at this point we have old config (if existed) as "bak" and the
			// new config as "tmp".
			boolean ok = cfigTmp.renameTo(cfigFile);
			if (ok) {
				// at this point we have the new config "activated", and the old
				// config (if it existed) as "bak"
				cfigBak.delete(); // clean up
			} else {
				// this codepath represents a tiny failure window. The load processing
				// on startup will detect missing config and will attempt to start
				// with "tmp" (latest), then "bak" (the previous). We can also end up
				// here if we failed to rename the current config to "bak". In that
				// case we will restart with the previous state.
				throw new IOException(Messages.getString("cfig.unableToSave", cfigTmp.getAbsolutePath())); //$NON-NLS-1$
			}
		}
	}
	
	private void writeConfigIni(File configDir) {
		try {
			File configIni = new File(configDir, CONFIG_INI);
			if (!configIni.exists()) {
				URL configIniURL = ConfigurationActivator.getBundleContext().getBundle().getEntry(CONFIG_INI);
				copy(configIniURL, configIni);
			}
		} catch (Exception e) {
			System.out.println(Messages.getString("cfg.unableToCreateConfig.ini"));
		}
	}

	public static PlatformConfiguration getCurrent() {
		return currentPlatformConfiguration;
	}

	/**
	 * Create and initialize the current platform configuration
	 * @param cmdArgs command line arguments (startup and boot arguments are
	 * already consumed)
	 * @param r10apps application identifies as passed on the BootLoader.run(...)
	 * method. Supported for R1.0 compatibility.
	 */
	public static synchronized void startup(URL installURL, Location platformConfigLocation) throws Exception {
		PlatformConfiguration.installURL = installURL;
	
		// create current configuration
		if (currentPlatformConfiguration == null) {
			currentPlatformConfiguration = new PlatformConfiguration(platformConfigLocation);
			if (currentPlatformConfiguration.config == null)
				throw new Exception("Cannot load configuration from " + platformConfigLocation.getURL());
			if (currentPlatformConfiguration.config.isDirty())
				currentPlatformConfiguration.save();
		}
	}

	public static synchronized void shutdown() throws IOException {

		// save platform configuration
		PlatformConfiguration config = getCurrent();
		if (config != null) {
			// only save if there are changes in the config
//			// TODO clean this up when merging with the rest of update code
//			long lastStamp = config.config.getDate().getTime();
//			long computedStamp = config.computeChangeStamp();
			if (config.config.isDirty() /* || computedStamp > lastStamp */) {
				try {
					config.save();
				} catch (IOException e) {
					Utils.debug("Unable to save configuration " + e.toString()); //$NON-NLS-1$
					// will recover on next startup
				}
			}
			config.clearConfigurationLock();
		}
	}


	private synchronized void initializeCurrent(Location platformConfigLocation) throws IOException {
		// FIXME: commented out for now. Remove if not needed.
		//boolean concurrentUse = false;

		// Configuration URL was is specified by the OSGi layer. 
		// Default behavior is to look
		// for configuration in the specified meta area. If not found, look
		// for pre-initialized configuration in the installation location.
		// If it is found it is used as the initial configuration. Otherwise
		// a new configuration is created. In either case the resulting
		// configuration is written into the specified configuration area.

		URL configFileURL = new URL(platformConfigLocation.getURL(),CONFIG_NAME);
		try {	
			// check concurrent use lock
			// FIXME: might not need this method call.
			getConfigurationLock(configFileURL);

			// try loading the configuration
			try {
				config = loadConfig(configFileURL);
				Utils.debug("Using configuration " + configFileURL.toString()); //$NON-NLS-1$
			} catch (Exception e) {
				// failed to load, see if we can find pre-initialized configuration.
				try {
					Location parentLocation = platformConfigLocation.getParentLocation();
					if (parentLocation == null)
						throw new IOException(); // no platform.xml found, need to create default site
					
					URL sharedConfigFileURL = new URL(parentLocation.getURL(), CONFIG_NAME);

					config = loadConfig(sharedConfigFileURL);
					
					// pre-initialized config loaded OK ... copy any remaining update metadata
					// Only copy if the default config location is not the install location
					if (!sharedConfigFileURL.equals(configFileURL)) {
//						if (true)
							// need to link config info instead of using a copy
							linkInitializedState(config, parentLocation, platformConfigLocation);
//						else
//							// copy config info
//							copyInitializedState(sharedConfigDirURL, configPath);
						
						Utils.debug("Configuration initialized from    " + sharedConfigFileURL.toString()); //$NON-NLS-1$
					}
					return;
				} catch (Exception ioe) {
					Utils.debug("Creating default configuration from " + configFileURL.toExternalForm());
					createDefaultConfiguration(configFileURL);
				}
			}
		} finally {
			configLocation = configFileURL;
			if (config.getURL() == null)
				config.setURL(configFileURL);
			verifyPath(configLocation);
			Utils.debug("Creating configuration " + configFileURL.toString()); //$NON-NLS-1$
		}
	}

	
	private synchronized void initialize(URL url) throws Exception {
		if (url != null) {
			config = loadConfig(url);	
			Utils.debug("Using configuration " + url.toString()); //$NON-NLS-1$
		}
		if (config == null) {
			config = new Configuration();		
			Utils.debug("Creating empty configuration object"); //$NON-NLS-1$
		}
		config.setURL(url);
		configLocation = url;
	}

	private void createDefaultConfiguration(URL url)throws IOException{
		// we are creating new configuration
		config = new Configuration();
		config.setURL(url);
		SiteEntry defaultSite = (SiteEntry)getRootSite();
		configureSite(defaultSite);
		try {
			// parse the site directory to discover features
			defaultSite.loadFromDisk(0);
		} catch (CoreException e1) {
			Utils.log("Cannot load default site " + defaultSite.getResolvedURL());
			return;
		}
	}
	private ISiteEntry getRootSite() {
		// create default site entry for the root
		ISitePolicy defaultPolicy = createSitePolicy(DEFAULT_POLICY_TYPE, DEFAULT_POLICY_LIST);
		URL siteURL = null;
		try {
			siteURL = new URL(PlatformURLHandler.PROTOCOL + PlatformURLHandler.PROTOCOL_SEPARATOR + "/" + "base" + "/"); //$NON-NLS-1$ //$NON-NLS-2$ // try using platform-relative URL
		} catch (MalformedURLException e) {
			siteURL = getInstallURL(); // ensure we come up ... use absolute file URL
		}
		ISiteEntry defaultSite = createSiteEntry(siteURL, defaultPolicy);
		return defaultSite;
	}

//	private void resetInitializationConfiguration(URL url) throws IOException {
//		// [20111]
//		if (!supportsDetection(url))
//			return; // can't do ...
//
//		URL resolved = resolvePlatformURL(url);
//		File initCfg = new File(resolved.getFile().replace('/', File.separatorChar));
//		File initDir = initCfg.getParentFile();
//		resetInitializationLocation(initDir);
//	}

	private void resetInitializationLocation(File dir) {
		// [20111]
		if (dir == null || !dir.exists() || !dir.isDirectory())
			return;
		File[] list = dir.listFiles();
		for (int i = 0; i < list.length; i++) {
			if (list[i].isDirectory())
				resetInitializationLocation(list[i]);
			list[i].delete();
		}
	}

	private boolean getConfigurationLock(URL url) {

//		if (!url.getProtocol().equals("file")) //$NON-NLS-1$
//			return false;
//
//		verifyPath(url);
//		String cfgName = url.getFile().replace('/', File.separatorChar);
//		String lockName = cfgName + CONFIG_FILE_LOCK_SUFFIX;
//		cfgLockFile = new File(lockName);
//
//		//if the lock file already exists, try to delete,
//		//assume failure means another eclipse has it open
//		if (cfgLockFile.exists())
//			cfgLockFile.delete();
//		if (cfgLockFile.exists()) {
//			throw new RuntimeException(Policy.bind("cfig.inUse", cfgName, lockName)); //$NON-NLS-1$
//		}
//
//		// OK so far ... open the lock file so other instances will fail
//		try {
//			cfgLockFileRAF = new RandomAccessFile(cfgLockFile, "rw"); //$NON-NLS-1$
//			cfgLockFileRAF.writeByte(0);
//		} catch (IOException e) {
//			throw new RuntimeException(Policy.bind("cfig.failCreateLock", cfgName)); //$NON-NLS-1$
//		}

		return false;
	}

	private void clearConfigurationLock() {
		try {
			if (cfgLockFileRAF != null) {
				cfgLockFileRAF.close();
				cfgLockFileRAF = null;
			}
		} catch (IOException e) {
			// ignore ...
		}
		if (cfgLockFile != null) {
			cfgLockFile.delete();
			cfgLockFile = null;
		}
	}

	private long computeChangeStamp() {
		featuresChangeStamp = computeFeaturesChangeStamp();
		pluginsChangeStamp = computePluginsChangeStamp();
		changeStamp = Math.max(featuresChangeStamp, pluginsChangeStamp);
		// round off to seconds
		changeStamp = (changeStamp/1000)*1000;
		return changeStamp;
	}

	private long computeFeaturesChangeStamp() {
		if (featuresChangeStampIsValid)
			return featuresChangeStamp;

		long result = 0;
		ISiteEntry[] sites = config.getSites();
		for (int i = 0; i < sites.length; i++) {
			result = Math.max(result, sites[i].getFeaturesChangeStamp());
		}
		featuresChangeStamp = result;
		featuresChangeStampIsValid = true;
		return featuresChangeStamp;
	}

	private long computePluginsChangeStamp() {
		if (pluginsChangeStampIsValid)
			return pluginsChangeStamp;

		long result = 0;
		ISiteEntry[] sites = config.getSites();
		for (int i = 0; i < sites.length; i++) {
			result = Math.max(result, sites[i].getPluginsChangeStamp());
		}
		pluginsChangeStamp = result;
		pluginsChangeStampIsValid = true;
		return pluginsChangeStamp;
	}

	private void configureExternalLinks() {
		URL linkURL = getInstallURL();
		if (!supportsDetection(linkURL))
			return;

		try {
			linkURL = new URL(linkURL, LINKS + "/"); //$NON-NLS-1$
		} catch (MalformedURLException e) {
			// skip bad links ...
			Utils.debug("Unable to obtain link URL"); //$NON-NLS-1$
			return;
		}

		File linkDir = new File(linkURL.getFile());
		File[] links = linkDir.listFiles();
		if (links == null || links.length == 0) {
			Utils.debug("No links detected in " + linkURL.toExternalForm()); //$NON-NLS-1$
			return;
		}

		for (int i = 0; i < links.length; i++) {
			if (links[i].isDirectory())
				continue;
			Utils.debug("Link file " + links[i].getAbsolutePath()); //$NON-NLS-1$
			Properties props = new Properties();
			FileInputStream is = null;
			try {
				is = new FileInputStream(links[i]);
				props.load(is);
				configureExternalLinkSite(links[i], props);
			} catch (IOException e) {
				// skip bad links ...
				Utils.debug("   unable to load link file " + e); //$NON-NLS-1$
				continue;
			} finally {
				if (is != null) {
					try {
						is.close();
					} catch (IOException e) {
						// ignore ...
					}
				}
			}
		}
	}

	private void configureExternalLinkSite(File linkFile, Properties props) {
		String path = props.getProperty(LINK_PATH);
		if (path == null) {
			Utils.debug("   no path definition"); //$NON-NLS-1$
			return;
		}

		String link;
		boolean updateable = true;
		URL siteURL;

		// parse out link information
		if (path.startsWith(LINK_READ + " ")) { //$NON-NLS-1$
			updateable = false;
			link = path.substring(2).trim();
		} else if (path.startsWith(LINK_READ_WRITE + " ")) { //$NON-NLS-1$
			link = path.substring(3).trim();
		} else {
			link = path;
		}

		// 	make sure we have a valid link specification
		try {
			if (!link.endsWith(File.separator))
				link += File.separator;
			File target = new File(link + ECLIPSE);
			link = "file:" + target.getAbsolutePath().replace(File.separatorChar, '/'); //$NON-NLS-1$
			if (!link.endsWith("/")) //$NON-NLS-1$
				link += "/"; // sites must be directories //$NON-NLS-1$
			siteURL = new URL(link);
			if (findConfiguredSite(siteURL, true) != null)
				// linked site is already known
				return;
		} catch (MalformedURLException e) {
			// ignore bad links ...
			Utils.debug("  bad URL " + e); //$NON-NLS-1$
			return;
		}
		
		// process the link
		SiteEntry linkSite = (SiteEntry) externalLinkSites.get(siteURL);
		if (linkSite == null) {
			// this is a link to a new target so create site for it
			ISitePolicy linkSitePolicy = createSitePolicy(DEFAULT_POLICY_TYPE, DEFAULT_POLICY_LIST);
			linkSite = (SiteEntry) createSiteEntry(siteURL, linkSitePolicy);
		}
		// update site entry if needed
		linkSite.setUpdateable(updateable);
		linkSite.setLinkFileName(linkFile.getAbsolutePath());

		// configure the new site
		// NOTE: duplicates are not replaced (first one in wins)
		configureSite(linkSite);
		// there are changes in the config
		config.setDirty(true);
		Utils.debug("   " + (updateable ? "R/W -> " : "R/O -> ") + siteURL.toString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}

	private void validateSites() {

		// check to see if all sites are valid. Remove any sites that do not exist.
		SiteEntry[] list = config.getSites();
		for (int i = 0; i < list.length; i++) {
			URL siteURL = list[i].getResolvedURL();
			if (!supportsDetection(siteURL))
				continue;

			File siteRoot = new File(siteURL.getFile().replace('/', File.separatorChar));
			if (!siteRoot.exists()) {
				unconfigureSite(list[i]);
				Utils.debug("Site " + siteURL + " does not exist ... removing from configuration"); //$NON-NLS-1$ //$NON-NLS-2$
			}
			
			// If multiple paths are defined in the same link file
			// or if the path changes, the old site will still be kept.
			// A better algorithm could be implemented by keeping track 
			// of the previous content of the link file.
			// TODO do the above
			String linkName = list[i].getLinkFileName();
			if (linkName != null) {
				File linkFile = new File(linkName);
				if (!linkFile.exists())  {
					unconfigureSite(list[i]);
					config.setDirty(true);
					Utils.debug("Site " + siteURL + " is no longer linked ... removing from configuration"); //$NON-NLS-1$ //$NON-NLS-2$	
				}
			}
		}
	}
	
	private void linkInitializedState(Configuration sharedConfig, Location sharedConfigLocation, Location newConfigLocation) {
		try {
			URL oldConfigIniURL = new URL(sharedConfigLocation.getURL(), CONFIG_INI);
			URL newConfigIniURL = new URL(newConfigLocation.getURL(), CONFIG_INI);
			if (!newConfigIniURL.getProtocol().equals("file")) //$NON-NLS-1$
				return; // need to be able to do write

			// modify config.ini and platform.xml to only link original files
			File configIni = new File(newConfigIniURL.getFile());
			Properties props = new Properties();
			props.put("osgi.sharedConfiguration.area", sharedConfigLocation.getURL().toExternalForm());
			props.store(new FileOutputStream(configIni), "Linked configuration");
			
			config = new Configuration(new Date());
			config.setURL(new URL(newConfigLocation.getURL(), CONFIG_NAME));
			config.setLinkedConfig(sharedConfig);
			config.setDirty(true);
		} catch (IOException e) {
			// this is an optimistic copy. If we fail, the state will be reconciled
			// when the update manager is triggered.
			System.out.println(e);
		}
	}

//	private void copyInitializedState(URL source, String target) {
//		try {
//			if (!source.getProtocol().equals("file")) //$NON-NLS-1$
//				return; // need to be able to do "dir"
//
//			copy(new File(source.getFile()), new File(target));
//
//		} catch (IOException e) {
//			// this is an optimistic copy. If we fail, the state will be reconciled
//			// when the update manager is triggered.
//		}
//	}

	private void copy(File src, File tgt) throws IOException {
		if (src.isDirectory()) {
			// copy content of directories
			tgt.mkdir();
			FilenameFilter filter = new FilenameFilter() {
				public boolean accept(File dir, String name) {
					return !name.equals(ConfigurationActivator.LAST_CONFIG_STAMP);
				}
			};
			File[] list = src.listFiles(filter);
			if (list == null)
				return;
			for (int i = 0; i < list.length; i++) {
				copy(list[i], new File(tgt, list[i].getName()));
			}
		} else {
			// copy individual files
			FileInputStream is = null;
			FileOutputStream os = null;
			try {
				is = new FileInputStream(src);
				os = new FileOutputStream(tgt);
				byte[] buff = new byte[1024];
				int count = is.read(buff);
				while (count != -1) {
					os.write(buff, 0, count);
					count = is.read(buff);
				}
			} catch (IOException e) {
				// continue ... update reconciler will have to reconstruct state
			} finally {
				if (is != null)
					try {
						is.close();
					} catch (IOException e) {
						// ignore ...
					}
				if (os != null)
					try {
						os.close();
					} catch (IOException e) {
						// ignore ...
					}
			}
		}
	}

	private void copy(URL src, File tgt) throws IOException {
		InputStream is = null;
		OutputStream os = null;
		try {
			is = src.openStream();
			os = new FileOutputStream(tgt);
			byte[] buff = new byte[1024];
			int count = is.read(buff);
			while (count != -1) {
				os.write(buff, 0, count);
				count = is.read(buff);
			}
		} finally {
			if (is != null)
				try {
					is.close();
				} catch (IOException e) {
					// ignore ...
				}
			if (os != null)
				try {
					os.close();
				} catch (IOException e) {
					// ignore ...
				}
		}
	}		
	private Configuration loadConfig(URL url) throws Exception {
		if (url == null)
			throw new IOException(Messages.getString("cfig.unableToLoad.noURL")); //$NON-NLS-1$

		// try to load saved configuration file (watch for failed prior save())
		ConfigurationParser parser = null;
		try {
			parser = new ConfigurationParser();
		} catch (InvocationTargetException e) {
			throw (Exception)e.getTargetException();
		}
		
		config = null;
		Exception originalException = null;
		try {
			config = parser.parse(url);
		} catch (Exception e1) {
			// check for save failures, so open temp and backup configurations
			originalException = e1;
			try {
				URL tempURL = new URL(url.toExternalForm()+CONFIG_FILE_TEMP_SUFFIX);
				config = parser.parse(tempURL); 
			} catch (Exception e2) {
				try {
					URL backupUrl = new URL(url.toExternalForm()+CONFIG_FILE_BAK_SUFFIX);
					config = parser.parse(backupUrl);
				} catch (IOException e3) {
					throw originalException; // we tried, but no config here ...
				}
			}
		}

		return config;
	}
	

	private String loadAttribute(Properties props, String name, String dflt) {
		String prop = props.getProperty(name);
		if (prop == null)
			return dflt;
		else
			return prop.trim();
	}

	private void loadInitializationAttributes() {

		// look for the product initialization file relative to the install location
		URL url = getInstallURL();

		// load any initialization attributes. These are the default settings for
		// key attributes (eg. default primary feature) supplied by the packaging team.
		// They are always reloaded on startup to pick up any changes due to
		// "native" updates.
		Properties initProps = new Properties();
		InputStream is = null;
		try {
			URL initURL = new URL(url, CONFIG_FILE_INIT);
			is = initURL.openStream();
			initProps.load(is);
			Utils.debug("Defaults from " + initURL.toExternalForm()); //$NON-NLS-1$
		} catch (IOException e) {
			return; // could not load default settings
		} finally {
			if (is != null) {
				try {
					is.close();
				} catch (IOException e) {
					// ignore ...
				}
			}
		}

		// use default settings if supplied
		String initId = loadAttribute(initProps, INIT_DEFAULT_FEATURE_ID, null);
		if (initId != null) {
			String application = loadAttribute(initProps, INIT_DEFAULT_FEATURE_APPLICATION, null);
			String initPluginId = loadAttribute(initProps, INIT_DEFAULT_PLUGIN_ID, null);
			if (initPluginId == null)
				initPluginId = initId;
			IFeatureEntry fe = findConfiguredFeatureEntry(initId);

			if (fe == null) {
				// bug 26896 : setup optimistic reconciliation if the primary feature has changed or is new
				// create entry if not exists
				fe = createFeatureEntry(initId, null, initPluginId, null, true, application, null);
				configureFeatureEntry(fe);
			} 
			if (config != null)
				config.setDefaultFeature(initId);
			if (ConfigurationActivator.DEBUG) {
				Utils.debug("    Default primary feature: " + initId); //$NON-NLS-1$
				if (application != null)
					Utils.debug("    Default application    : " + application); //$NON-NLS-1$
			}
		}
	}

//
//	private static String[] checkForNewUpdates(IPlatformConfiguration cfg, String[] args) {
//		try {
//			URL markerURL = new URL(cfg.getConfigurationLocation(), CHANGES_MARKER);
//			File marker = new File(markerURL.getFile());
//			if (!marker.exists())
//				return args;
//
//			// indicate -newUpdates
//			marker.delete();
//			String[] newArgs = new String[args.length + 1];
//			newArgs[0] = CMD_NEW_UPDATES;
//			System.arraycopy(args, 0, newArgs, 1, args.length);
//			return newArgs;
//		} catch (MalformedURLException e) {
//			return args;
//		}
//	}

	public static boolean supportsDetection(URL url) {
		String protocol = url.getProtocol();
		if (protocol.equals("file")) //$NON-NLS-1$
			return true;
		else if (protocol.equals(PlatformURLHandler.PROTOCOL)) {
			URL resolved = null;
			try {
				resolved = resolvePlatformURL(url); // 19536
			} catch (IOException e) {
				return false; // we tried but failed to resolve the platform URL
			}
			return resolved.getProtocol().equals("file"); //$NON-NLS-1$
		} else
			return false;
	}

	private static void verifyPath(URL url) {
		String protocol = url.getProtocol();
		String path = null;
		if (protocol.equals("file")) //$NON-NLS-1$
			path = url.getFile();
		else if (protocol.equals(PlatformURLHandler.PROTOCOL)) {
			URL resolved = null;
			try {
				resolved = resolvePlatformURL(url); // 19536
				if (resolved.getProtocol().equals("file")) //$NON-NLS-1$
					path = resolved.getFile();
			} catch (IOException e) {
				// continue ...
			}
		}

		if (path != null) {
			File dir = new File(path).getParentFile();
			if (dir != null)
				dir.mkdirs();
		}
	}

	public static URL resolvePlatformURL(URL url) throws IOException {
		// 19536
		if (url.getProtocol().equals(PlatformURLHandler.PROTOCOL)) {
			URLConnection connection = url.openConnection();
			if (connection instanceof PlatformURLConnection) {
				url = ((PlatformURLConnection) connection).getResolvedURL();
			} else {
				//				connection = new PlatformURLBaseConnection(url);
				//				url = ((PlatformURLConnection)connection).getResolvedURL();
				url = getInstallURL();
			}
		}
		return url;
	}


	public static URL getInstallURL() {
		return installURL;
	}
	
	private void saveAsXML(OutputStream stream) throws CoreException {	
		try {
			DocumentBuilder docBuilder = documentBuilderFactory.newDocumentBuilder();
			Document doc = docBuilder.newDocument();

			if (config == null)
				throw Utils.newCoreException("Configuration cannot be saved because it does not exist",null);
			
			config.setDate(new Date());
			Element configElement = config.toXML(doc);
			doc.appendChild(configElement);

			// Write out to a file
			
			Transformer transformer=transformerFactory.newTransformer();
			transformer.setOutputProperty(OutputKeys.METHOD, "xml");
			transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
			transformer.setOutputProperty(OutputKeys.INDENT, "yes");
			DOMSource source = new DOMSource(doc);
			StreamResult result = new StreamResult(stream);

			transformer.transform(source,result);
			stream.close();
		} catch (Exception e) {
			throw Utils.newCoreException("", e);
		}  
	}
	
	private void reconcile() throws CoreException {
		long lastChange = config.getDate().getTime();
		SiteEntry[] sites = config.getSites();
		for (int s=0; s<sites.length; s++) {
			long siteTimestamp = sites[s].getChangeStamp();
			if (siteTimestamp > lastChange)
				sites[s].loadFromDisk(lastChange);
		}
		config.setDirty(true);
	}
	
	public Configuration getConfiguration() {
		return config;
	}
}
