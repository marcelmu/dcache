package org.dcache.gplazma.plugins;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Predicates.instanceOf;
import static com.google.common.collect.Iterables.any;
import static com.google.common.collect.Iterables.find;
import static org.dcache.gplazma.util.Preconditions.checkAuthentication;

import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketException;
import java.security.Principal;
import java.security.cert.CRLException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import javax.security.auth.x500.X500Principal;

import org.dcache.auth.LoginNamePrincipal;
import org.dcache.auth.UserNamePrincipal;
import org.dcache.gplazma.AuthenticationException;
import org.dcache.gplazma.util.CertificateUtils;
import org.dcache.util.NetworkUtils;
import org.glite.voms.PKIVerifier;
import org.glite.voms.VOMSAttribute;
import org.glite.voms.VOMSValidator;
import org.glite.voms.ac.ACValidator;
import org.glite.voms.ac.AttributeCertificate;
import org.globus.gsi.GlobusCredential;
import org.globus.gsi.GlobusCredentialException;
import org.ietf.jgss.GSSException;
import org.opensciencegrid.authz.xacml.client.MapCredentialsClient;
import org.opensciencegrid.authz.xacml.common.LocalId;
import org.opensciencegrid.authz.xacml.common.XACMLConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import com.google.common.base.Preconditions;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;

/**
 * Responsible for taking an X509Certificate chain from the public credentials
 * and adding a {@link UserNamePrincipal} based on a mapping for the local
 * storage resource returned from a GUMS/XACML service.<br>
 * <br>
 *
 * The authentication method is an alternative to straight VOMS authentication;
 * it requires that the X509 proxy contain the following VOMS extensions: <br>
 * <br>
 *
 * <ul>
 * <li>VO</li>
 * <li>VOMS subject</li>
 * <li>VOMS issuer</li>
 * <li>attribute (FQAN)</li>
 * </ul>
 * <br>
 *
 * The gplazma.conf file definition line for this plugin can contain the
 * following property definitions: <br>
 *
 * <table>
 * <tr>
 * <th>PROPERTY</th>
 * <th>DEFAULT VALUE</th>
 * <th>DESCRIPTION</th>
 * </tr>
 * <tr>
 * <td>gplazma.voms.validate</td>
 * <td>true</td>
 * <td>whether the VOMS attributes contained in the certificate chain should be
 * validated (this requires a non-empty local VOMS directory)</td>
 * </tr>
 * <tr>
 * <td>gplazma.vomsdir.dir</td>
 * <td>/etc/grid-security/vomsdir</td>
 * <td>location of VOMS authority subdirs & .lsc files</td>
 * </tr>
 * <tr>
 * <td>gplazma.vomsdir.ca</td>
 * <td>/etc/grid-security/certificates</td>
 * <td>location of CA certs used in VOMS validation</td>
 * </tr>
 * <tr>
 * <td>gplazma.xacml.service.url</td>
 * <td>(required)</td>
 * <td>location of the XACML service to contact for mapping</td>
 * </tr>
 * <tr>
 * <td>gplazma.xacml.client.type</td>
 * <td><code>org.dcache.gplazma.plugins.PrivilegeDelegate</code></td>
 * <td>client implementation (the default is a simple wrapper around
 * <code>org.opensciencegrid.authz.xacml.client.MapCredentialsClient</code>)</td>
 * </tr>
 * <tr>
 * <td>gplazma.xacml.cachelife.secs</td>
 * <td>30</td>
 * <td>time-to-live in local (in-memory) cache (between accesses) for a mapping
 * entry already fetched from the XACML service</td>
 * </tr>
 * <tr>
 * <td>gplazma.xacml.cache.maxsize</td>
 * <td>1024</td>
 * <td>maximum entries held in the cache</td>
 * </tr>
 * </table>
 *
 * @author arossi
 */
public final class XACMLPlugin implements GPlazmaAuthenticationPlugin {

    /**
     * Simple struct to hold the extensions extracted from the certificate
     * chain.
     *
     * @author arossi
     */
    private static class VomsExtensions {
        private String _x509Subject;
        private String _x509SubjectIssuer;
        private String _fqan;
        private boolean _primary;
        private String _vo;
        private String _vomsSubject;
        private String _vomsSubjectIssuer;

        @Override
        public boolean equals(Object object) {
            if (!(object instanceof VomsExtensions)) {
                return false;
            }

            return toString().equals(object.toString());
        }

        @Override
        public int hashCode() {
            return toString().hashCode();
        }

        @Override
        public String toString() {
            return VomsExtensions.class.getSimpleName() + "[X509Subject='"
                            + _x509Subject + "', X509SubjectIssuer='"
                            + _x509SubjectIssuer + "', fqan='" + _fqan
                            + "', primary=" + _primary + ", VO='" + _vo
                            + "', VOMSSubject='" + _vomsSubject
                            + "', VOMSSubjectIssuer='" + _vomsSubjectIssuer
                            + "']";
        }
    }

    /**
     * Does the work of contacting the XACML server to get a mapping not in the
     * cache.
     *
     * @author arossi
     */
    private class XACMLFetcher extends CacheLoader<VomsExtensions, LocalId> {
        /*
         * (non-Javadoc) Contacts the XACML/GUMS service. Throws Authentication
         * Exception if an exception occurs or no mapping is found.
         *
         * @see com.google.common.cache.CacheLoader#load(java.lang.Object)
         */
        @Override
        public LocalId load(VomsExtensions key) throws AuthenticationException {
            logger.debug("No locally cached mapping for {}; contacting mapping service at {}",
                            key, _mappingServiceURL);

            final IMapCredentialsClient xacmlClient = newClient();
            xacmlClient.configure(_properties);
            xacmlClient.setX509Subject(key._x509Subject);
            xacmlClient.setX509SubjectIssuer(key._x509SubjectIssuer);
            xacmlClient.setFqan(key._fqan);
            xacmlClient.setVO(key._vo);
            xacmlClient.setVOMSSigningSubject(key._vomsSubject);
            xacmlClient.setVOMSSigningIssuer(key._vomsSubjectIssuer);
            xacmlClient.setResourceType(XACMLConstants.RESOURCE_SE);
            xacmlClient.setResourceDNSHostName(_resourceDNSHostName);
            xacmlClient.setResourceX509ID(_targetServiceName);
            xacmlClient.setResourceX509Issuer(_targetServiceIssuer);
            xacmlClient.setRequestedaction(XACMLConstants.ACTION_ACCESS);

            LocalId localId = xacmlClient.mapCredentials(_mappingServiceURL);
            Preconditions.checkArgument(localId != null, DENIED_MESSAGE + key);

            logger.debug("mapping service returned localId {} ", localId);
            return localId;
        }
    }

    static final String CADIR = "gplazma.vomsdir.ca";
    static final String VOMSDIR = "gplazma.vomsdir.dir";
    static final String VATTR_VALIDATE = "gplazma.voms.validate";
    static final String ILLEGAL_CACHE_SIZE = "cache size must be non-zero positive integer; was: ";
    static final String ILLEGAL_CACHE_LIFE = "cache life must be positive integer; was: ";
    static final String DENIED_MESSAGE = "Permission Denied: "
                    + "No XACML mapping retrieved for extensions ";
    static final String HOST_CREDENTIAL_ERROR = "Could not load host globus credentials ";
    static final String SERVICE_URL_PROPERTY = "gplazma.xacml.service.url";
    static final String CLIENT_TYPE_PROPERTY = "gplazma.xacml.client.type";
    static final String SERVICE_KEY = "gplazma.xacml.hostkey";
    static final String SERVICE_CERT = "gplazma.xacml.hostcert";
    static final String SERVICE_CA = "gplazma.xacml.ca";
    static final String CACHE_LIFETIME = "gplazma.xacml.cachelife.secs";
    static final String CACHE_SIZE = "gplazma.xacml.cache.maxsize";

    private static final Logger logger = LoggerFactory.getLogger(XACMLPlugin.class);

    /*
     * caching enabled by default
     */
    private static final String DEFAULT_CACHE_LIFETIME = "30";
    private static final String DEFAULT_CACHE_SIZE = "1024";

    /*
     * Optimization for rapid sequential storage operation requests. Cache is
     * first searched before going to the (remote) XACML service. Each entry has
     * a short time-to-live by default (30 seconds).
     */
    private Cache<VomsExtensions, LocalId> _localIdCache;

    /*
     * for VOMS attribute extraction
     */
    private final String _caDirectory;
    private final String _vomsDirectory;
    private final Map<?, ?> _mdcContext;

    /*
     * VOMS attribute validation turned off by default
     */
    private boolean _vomsAttrValidate = false;

    /*
     * the XACML service
     */
    private final String _mappingServiceURL;

    /*
     * passed to XACML client configure()
     */
    private final Properties _properties;

    /*
     * for XACML client configuration
     */
    private Class _clientType;
    private String _targetServiceName;
    private String _targetServiceIssuer;
    private String _resourceDNSHostName;

    /**
     * Configures VOMS extension validation, XACML service location, local id
     * caching and storage resource information.
     *
     * @param properties
     * @throws ClassNotFoundException
     * @throws GSSException
     * @throws SocketException
     */
    public XACMLPlugin(Properties properties) throws ClassNotFoundException,
                    GSSException, SocketException {
        _properties = properties;

        /*
         * VOMS setup
         */
        final String pki = properties.getProperty(VATTR_VALIDATE);
        if (pki != null) {
            _vomsAttrValidate = Boolean.parseBoolean(pki);
        }
        _caDirectory = properties.getProperty(CADIR);
        _vomsDirectory = properties.getProperty(VOMSDIR);
        _mdcContext = MDC.getCopyOfContextMap();

        /*
         * Adds SSL system properties required by privilege library.
         */
        System.setProperty("sslCAFiles", properties.getProperty(SERVICE_CA) + "/*.0");
        System.setProperty("sslCertfile", properties.getProperty(SERVICE_CERT));
        System.setProperty("sslKey", properties.getProperty(SERVICE_KEY));

        /*
         * XACML setup
         */
        _mappingServiceURL = properties.getProperty(SERVICE_URL_PROPERTY);
        checkArgument(_mappingServiceURL != null, "Undefined property: "
                        + SERVICE_URL_PROPERTY);
        setClientType(properties.getProperty(CLIENT_TYPE_PROPERTY));
        configureTargetServiceInfo();
        configureResourceDNSHostName();

        /*
         * LocalId Cache setup
         */
        configureCache();

        logger.info("XACML plugin now loaded for URL {}", _mappingServiceURL);
    }

    /*
     * (non-Javadoc) Combines authentication and XACML mapping into one step by
     * extracting (and optionally validating) the VOMS extensions necessary for
     * the XACML client configuration, then retrieving the (first valid) mapping
     * from the XACML service and adding it as a UserNamePrincipal to the
     * identified principals.  Note that if there already exists a
     * UserNamePrincipal, an AuthenticationException is thrown.
     *
     * Calls {@link #extractExensionsFromChain(X509Certificate[], Set,
     * VOMSValidator)} and {@link #getMappingFor(Set)}.
     *
     * @see
     * org.dcache.gplazma.plugins.GPlazmaAuthenticationPlugin#authenticate(org
     * .dcache.gplazma.SessionID, java.util.Set, java.util.Set, java.util.Set)
     */
    @Override
    public void authenticate(Set<Object> publicCredentials,
                    Set<Object> privateCredentials,
                    Set<Principal> identifiedPrincipals)
                    throws AuthenticationException {
        checkAuthentication(
                !any(identifiedPrincipals, instanceOf(UserNamePrincipal.class)),
                "username already defined");

        /*
         * validator not thread-safe; reinstantiated with each authenticate call
         */
        final VOMSValidator validator
            = new VOMSValidator(null,new ACValidator(getPkiVerifier()));
        final Set<VomsExtensions> extensions = new HashSet<VomsExtensions>();

        /*
         * extract all sets of extensions from certificate chains
         */
        for (final Object credential : publicCredentials) {
            if (credential instanceof X509Certificate[]) {
                extractExtensionsFromChain((X509Certificate[])  credential,
                                                                extensions,
                                                                validator);
            }
        }

        checkAuthentication(!extensions.isEmpty(), "no FQANs found");

        final Principal login
            = find(identifiedPrincipals, instanceOf(LoginNamePrincipal.class), null);

        /*
         * retrieve the first valid mapping and add it to the identified
         * principals
         */
        final String userName = getMappingFor(login, extensions);
        checkAuthentication(userName != null, "no mapping for: " + extensions);

        identifiedPrincipals.add(new UserNamePrincipal(userName));
    }

    /**
     * Sets up the local id cache.
     *
     * @throws IllegalArgumentException
     *             if the CACHE_LIFETIME is set to <0
     */
    private void configureCache() throws IllegalArgumentException {
        final int expiry
            = Integer.parseInt(_properties.getProperty(CACHE_LIFETIME,
                            DEFAULT_CACHE_LIFETIME));

        if (expiry < 0) {
            throw new IllegalArgumentException(ILLEGAL_CACHE_LIFE + expiry);
        }

        final int size
            = Integer.parseInt(_properties.getProperty(CACHE_SIZE,
                        DEFAULT_CACHE_SIZE));

        if (size < 1) {
            throw new IllegalArgumentException(ILLEGAL_CACHE_SIZE + size);
        }

        /*
         * constructed using strong references because the identity of the
         * extension set is based on String equals, not on instance ==.
         */
        _localIdCache = CacheBuilder.newBuilder()
                        .expireAfterAccess(expiry, TimeUnit.SECONDS)
                        .maximumSize(size)
                        .softValues()
                        .build(new XACMLFetcher());
    }

    /**
     * Extracts canonical DNS name of storage resource host from network
     * interfaces.
     *
     * @throws SocketException
     */
    private void configureResourceDNSHostName() throws SocketException {
        final List<InetAddress> addressList = NetworkUtils.getLocalAddresses();

        if (addressList.isEmpty()) {
            return;
        }

        Collections.sort(addressList, NetworkUtils.getExternalInternalSorter());
        _resourceDNSHostName = addressList.get(0).getCanonicalHostName();
    }

    /**
     * Extracts the identity and certificate issuer from the host certificate.
     *
     * @throws GSSException
     */
    private void configureTargetServiceInfo() throws GSSException {
        GlobusCredential serviceCredential;
        try {
            serviceCredential =
                new GlobusCredential(_properties.getProperty(SERVICE_CERT),
                                     _properties.getProperty(SERVICE_KEY));
        } catch (final GlobusCredentialException gce) {
            throw new GSSException(GSSException.NO_CRED, 0,
                            HOST_CREDENTIAL_ERROR + gce.toString());
        }
        _targetServiceName = serviceCredential.getIdentity();
        _targetServiceIssuer
            = CertificateUtils.toGlobusDN(serviceCredential.getIssuer(), true);
    }

    /**
     * Extracts VOMS extensions from the public credentials and adds them to the
     * running list.
     *
     * To preserve the feature of gPlazma1 which allows for XACML authentication
     * without having to store the .lsc files in /etc/grid-security/vomsdir, the
     * gplazma.voms.validate property is by default set to false. If
     * vomsAttrValidate is set to true, the verifier will attempt to validate
     * the VOMS attributes. In this case, the VOMSDIR needs to have a
     * subdirectory corresponding to the VO for the VOMS signer, containing the
     * necessary .lsc file(s).
     *
     * Calls {@link CertificateUtils#getVOMSAttribute(java.util.List, String)}
     *
     * TODO Update this method not to use the deprecated .parse() on the
     * VOMSValidator
     *
     * @param chain
     *            from the public credentials
     * @param extensionsSet
     *            all groups of extracted VOMS extensions
     * @param validator
     * @throws AuthenticationException
     */
    @SuppressWarnings("deprecation")
    private void extractExtensionsFromChain(X509Certificate[] chain,
                    Set<VomsExtensions> extensionsSet, VOMSValidator validator)
                    throws AuthenticationException {
        if (chain == null) {
            return;
        }

        final String proxySubject
            = CertificateUtils.getSubjectFromX509Chain(chain, false);
        /*
         * this is the issuer of the original cert in the chain (skips
         * impersonation proxies)
         */
        final String proxySubjectIssuer
            = CertificateUtils.getSubjectX509Issuer(chain, true);

        /*
         * VOMS signs the first cert in the chain; its subject will be the x509
         * subject issuer of that cert, not of the original
         */
        final String vomsSubject
            = CertificateUtils.getSubjectX509Issuer(chain, false);

        if (_vomsAttrValidate) {
            validator.setClientChain(chain).validate();
        } else {
            validator.setClientChain(chain).parse();
        }

        for (final Object attr : validator.getVOMSAttributes()) {
            final VOMSAttribute vomsAttr = (VOMSAttribute) attr;
            final VomsExtensions vomsExtensions = new VomsExtensions();
            vomsExtensions._x509Subject = proxySubject;
            vomsExtensions._x509SubjectIssuer = proxySubjectIssuer;
            vomsExtensions._vo = vomsAttr.getVO();
            vomsExtensions._vomsSubject = vomsSubject;

            final AttributeCertificate ac = vomsAttr.getAC();
            if (ac != null) {
                final X500Principal x500 = ac.getIssuer();
                if (x500 != null) {
                    vomsExtensions._vomsSubjectIssuer
                        = CertificateUtils.toGlobusDN(x500.toString(), true);
                }
            }

            boolean primary = true;
            for (final Object fqan : vomsAttr.getFullyQualifiedAttributes()) {
                vomsExtensions._fqan = (String) fqan;
                vomsExtensions._primary = primary;
                primary = false;
                logger.debug(" {} authenticate, adding voms extensions = {}",
                                this, vomsExtensions);
                extensionsSet.add(vomsExtensions);
            }
        }
    }

    /**
     * Convenience wrapper; loops through the set of extension groups and calls
     * out to {@link Cache#get(Object)}.
     *
     * @param login
     *            may be <code>null</code>
     * @param extensionSet
     *            all groups of extracted VOMS extensions
     * @return local id or <code>null</code> if no mapping is found
     */
    private String getMappingFor(final Principal login,
                    final Set<VomsExtensions> extensionSet) {

        for (final VomsExtensions extensions : extensionSet) {
            try {
                final LocalId localId = _localIdCache.get(extensions);
                final String name = localId.getUserName();
                if (login == null || login.getName().equals(name)) {
                    logger.debug("getMappingFor {} = {}", extensions, name);
                    return name;
                }
            } catch (final ExecutionException t) {
                /*
                 * Exception has already been logged inside the fetcher ...
                 */
                logger.debug("could not find mapping for {}; continuing ...",
                                extensions);
            }
        }
        return null;
    }

    /**
     * Calls {@link CertificateUtils#getPkiVerifier(String, String, Map)}
     *
     * @return singleton instance of verifier
     * @throws AuthenticationException
     */
    private PKIVerifier getPkiVerifier() throws AuthenticationException {
        try {
            return CertificateUtils.getPkiVerifier(_vomsDirectory,
                            _caDirectory, _mdcContext);
        } catch (final CertificateException t) {
            throw new AuthenticationException(t.getMessage(), t);
        } catch (final CRLException t) {
            throw new AuthenticationException(t.getMessage(), t);
        } catch (final IOException t) {
            logger.error("Failed to load PKI stores: {}", t.getMessage());
            throw new AuthenticationException(t.getMessage(), t);
        }
    }

    /**
     * Provides for possible alternate implementations of the XACML client by
     * delegating to an implementation of {@link IMapCredentialsClient} which
     * wraps the germane methods of the privilege class (
     * {@link MapCredentialsClient}; privilege itself provides no interface).
     *
     * @return new instance of the class set from the
     *         <code>gplazma.xacml.client.type</code> property.
     * @throws AuthenticationException
     */
    private IMapCredentialsClient newClient() throws AuthenticationException {
        try {
            IMapCredentialsClient newInstance
                = (IMapCredentialsClient)_clientType.newInstance();
            return newInstance;
        } catch (final InstantiationException t) {
            throw new AuthenticationException(t.getMessage(), t);
        } catch (final IllegalAccessException t) {
            throw new AuthenticationException(t.getMessage(), t);
        }
    }

    /**
     * If undefined, sets the default class.
     *
     * @param property
     *            as defined by <code>gplazma.xacml.client.type</code>; if
     *            <code>null</code>, the value which obtains is
     *            {@link PrivilegeDelegate}.
     * @throws ClassNotFoundException
     */
    private void setClientType(String property) throws ClassNotFoundException {
        if (property == null || property.length() == 0) {
            _clientType = PrivilegeDelegate.class;
        } else {
            _clientType = Class.forName(property, true,
                            Thread.currentThread().getContextClassLoader());
        }
    }
}
