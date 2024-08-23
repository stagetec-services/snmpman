package com.oneandone.snmpman;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snmp4j.TransportMapping;
import org.snmp4j.agent.BaseAgent;
import org.snmp4j.agent.CommandProcessor;
import org.snmp4j.agent.DefaultMOContextScope;
import org.snmp4j.agent.DefaultMOQuery;
import org.snmp4j.agent.DefaultMOServer;
import org.snmp4j.agent.DuplicateRegistrationException;
import org.snmp4j.agent.MOContextScope;
import org.snmp4j.agent.MOScope;
import org.snmp4j.agent.ManagedObject;
import org.snmp4j.agent.io.ImportMode;
import org.snmp4j.agent.mo.ext.StaticMOGroup;
import org.snmp4j.agent.mo.snmp.RowStatus;
import org.snmp4j.agent.mo.snmp.SnmpCommunityMIB;
import org.snmp4j.agent.mo.snmp.SnmpNotificationMIB;
import org.snmp4j.agent.mo.snmp.SnmpTargetMIB;
import org.snmp4j.agent.mo.snmp.StorageType;
import org.snmp4j.agent.mo.snmp.VacmMIB;
import org.snmp4j.agent.security.MutableVACM;
import org.snmp4j.mp.MPv3;
import org.snmp4j.security.SecurityLevel;
import org.snmp4j.security.SecurityModel;
import org.snmp4j.security.USM;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.Variable;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.TransportMappings;
import org.snmp4j.util.ThreadPool;

import com.oneandone.snmpman.configuration.AgentConfiguration;
import com.oneandone.snmpman.configuration.Device;
import com.oneandone.snmpman.configuration.Walks;
import com.oneandone.snmpman.configuration.modifier.CommunityContextModifier;
import com.oneandone.snmpman.configuration.modifier.ModifiedVariable;
import com.oneandone.snmpman.configuration.modifier.Modifier;
import com.oneandone.snmpman.configuration.modifier.VariableModifier;
import com.oneandone.snmpman.snmp.MOGroup;

/**
 * This is the core class of the {@code Snmpman}. The agent simulates the SNMP-capable devices.
 * <br>
 * This class can be instantiated via the constructor {@link #SnmpmanAgent(com.oneandone.snmpman.configuration.AgentConfiguration)}, which
 * requires an instance of the {@link com.oneandone.snmpman.configuration.AgentConfiguration}.
 */
@SuppressWarnings("deprecation")
public class SnmpmanAgent extends BaseAgent {
	private static final Logger log = LoggerFactory.getLogger(SnmpmanAgent.class);

    /**
     * The default charset for files being read.
     */
//    private static final Charset DEFAULT_CHARSET = Charset.forName("UTF-8");

    /**
     * The configuration of this agent.
     */
    private final AgentConfiguration configuration;

    /**
     * The list of managed object groups.
     */
    private final List<ManagedObject> groups = new ArrayList<>();

    /**
     * Initializes a new instance of an SNMP agent.
     *
     * @param configuration the configuration for this agent
     */
    public SnmpmanAgent(final AgentConfiguration configuration) {
        super(SnmpmanAgent.getBootCounterFile(configuration), SnmpmanAgent.getConfigurationFile(configuration), new CommandProcessor(new OctetString(MPv3.createLocalEngineID())));
        this.agent.setWorkerPool(ThreadPool.create("RequestPool", 3));
        this.configuration = configuration;
    }

    /**
     * Returns the name of {@code this} agent.
     * <br>
     * See {@code AgentConfiguration.name} for more information on the return value.
     *
     * @return the name of {@code this} agent.
     */
    public String getName() {
        return configuration.getName();
    }

    /**
     * Returns the boot-counter file for the specified agent.
     * <p>
     * This file will be created in the same directory as the {@link com.oneandone.snmpman.configuration.AgentConfiguration#getWalk()} file.
     *
     * @return the boot-counter file
     */
    private static File getBootCounterFile(final AgentConfiguration configuration) {
        return new File(configuration.getWalk().getParentFile(), SnmpmanAgent.encode(configuration.getName() + ".BC.cfg"));
    }

    /**
     * Returns the configuration file for the specified agent.
     * <p>
     * This file will be created in the same directory as the {@link com.oneandone.snmpman.configuration.AgentConfiguration#getWalk()} file.
     *
     * @return the configuration file
     */
    private static File getConfigurationFile(final AgentConfiguration configuration) {
        return new File(configuration.getWalk().getParentFile(), SnmpmanAgent.encode(configuration.getName() + ".Config.cfg"));
    }

    /**
     * Translates a string into {@code x-www-form-urlencoded} format. The method uses the <i>UTF-8</i> encoding scheme.
     *
     * @param string {@code String} to be translated
     * @return the translated {@code String}
     */
    private static String encode(final String string) {
        try {
            return URLEncoder.encode(string, "UTF-8");
        } catch (final UnsupportedEncodingException e) {
            log.error("UTF-8 encoding is unsupported");
            return string;
        }
    }

    /**
     * Returns the root OIDs of the bindings.
     *
     * @param bindings the variable bindings
     * @return the roots of the specified variable bindings
     */
    private static List<OID> getRoots(final SortedMap<OID, Variable> bindings) {
        final List<OID> potentialRoots = new ArrayList<>(bindings.size());

        OID last = null;
        for (final OID oid : bindings.keySet()) {
            if (last != null) {
                int min = Math.min(oid.size(), last.size());
                while (min > 0) {
                    if (oid.leftMostCompare(min, last) == 0) {
                        OID root = new OID(last.getValue(), 0, min);
                        potentialRoots.add(root);
                        break;
                    }
                    min--;
                }
            }
            last = oid;
        }
        Collections.sort(potentialRoots);

        final List<OID> roots = new ArrayList<>(potentialRoots.size());
        potentialRoots.stream().filter(potentialRoot -> potentialRoot.size() > 0).forEach(potentialRoot -> {
            OID trimmedPotentialRoot = new OID(potentialRoot.getValue(), 0, potentialRoot.size() - 1);
            while (trimmedPotentialRoot.size() > 0 && Collections.binarySearch(potentialRoots, trimmedPotentialRoot) < 0) {
                trimmedPotentialRoot.trim(1);
            }
            if (trimmedPotentialRoot.size() == 0 && !roots.contains(potentialRoot)) {
                roots.add(potentialRoot);
            }
        });

        log.trace("identified roots {}", roots);
        return roots;
    }

    /**
     * Starts this agent instance.
     *
     * @throws IOException signals that this agent could not be initialized by the {@link #init()} method
     */
    public void execute() throws IOException {
        this.init();
        this.loadConfig(ImportMode.REPLACE_CREATE);
        this.addShutdownHook();
        this.getServer().addContext(new OctetString("public"));
        this.getServer().addContext(new OctetString(""));
        // configure community index contexts
        for (final Long vlan : configuration.getDevice().getVlans()) {
            this.getServer().addContext(new OctetString(String.valueOf(vlan)));
        }

        this.finishInit();
        this.run();
        this.sendColdStartNotification();
    }

    @Override
    @SuppressWarnings({ "unchecked", "rawtypes" })
    protected void initTransportMappings() {
        log.trace("starting to initialize transport mappings for agent \"{}\"", configuration.getName());
        transportMappings = new TransportMapping[1];
        TransportMapping tm = TransportMappings.getInstance().createTransportMapping(configuration.getAddress());
        transportMappings[0] = tm;
    }

    @Override
    protected void registerManagedObjects() {
        unregisterDefaultManagedObjects(null);
        unregisterDefaultManagedObjects(new OctetString());
        final List<Long> vlans = configuration.getDevice().getVlans();
        for (final Long vlan : vlans) {
            unregisterDefaultManagedObjects(new OctetString(String.valueOf(vlan)));
        }

        log.trace("registering managed objects for agent \"{}\"", configuration.getName());
        for (final Long vlan : vlans) {
            try {
                Map<OID, Variable> bindings = Walks.readWalk(configuration.getWalk());

                final SortedMap<OID, Variable> variableBindings = this.getVariableBindings(configuration.getDevice(), bindings, new OctetString(String.valueOf(vlan)));

                final OctetString context = new OctetString(String.valueOf(vlan));

                final List<OID> roots = SnmpmanAgent.getRoots(variableBindings);
                for (final OID root : roots) {
                    MOGroup group = createGroup(root, variableBindings);
                    final Iterable<VariableBinding> subtree = generateSubtreeBindings(variableBindings, root);
                    DefaultMOContextScope scope = new DefaultMOContextScope(context, root, true, root.nextPeer(), false);
                    ManagedObject mo = server.lookup(new DefaultMOQuery(scope, false));
                    if (mo != null) {
                        for (final VariableBinding variableBinding : subtree) {
                            group = new MOGroup(variableBinding.getOid(), variableBinding.getOid(), variableBinding.getVariable());
                            scope = new DefaultMOContextScope(context, variableBinding.getOid(), true, variableBinding.getOid().nextPeer(), false);
                            mo = server.lookup(new DefaultMOQuery(scope, false));
                            if (mo != null) {
                                log.warn("could not register single OID at {} because ManagedObject {} is already registered.", variableBinding.getOid(), mo);
                            } else {
                                groups.add(group);
                                registerGroupAndContext(group, context);
                            }
                        }
                    } else {
                        groups.add(group);
                        registerGroupAndContext(group, context);
                    }
                }
            }
            catch (IOException e) {
                log.error("Could not read walk file " + configuration.getWalk().getAbsolutePath(), e);
            }
        }
        createAndRegisterDefaultContext();
    }

    private MOGroup createGroup(final OID root, final SortedMap<OID, Variable> variableBindings) {
        final SortedMap<OID, Variable> subtree = new TreeMap<>();
        variableBindings.entrySet().stream().filter(binding -> binding.getKey().size() >= root.size()).filter(
                binding -> binding.getKey().leftMostCompare(root.size(), root) == 0).forEach(
                        binding -> subtree.put(binding.getKey(), binding.getValue())
        );

        return new MOGroup(root, subtree);
    }

    /**
     * Creates the {@link StaticMOGroup} with all information necessary to register it to the server.
     */
    private void createAndRegisterDefaultContext() {
        try {
            Map<OID, Variable> bindings = Walks.readWalk(configuration.getWalk());
            final SortedMap<OID, Variable> variableBindings = this.getVariableBindings(configuration.getDevice(), bindings, new OctetString());
            final List<OID> roots = SnmpmanAgent.getRoots(variableBindings);
            for (final OID root : roots) {
                MOGroup group = createGroup(root, variableBindings);
                registerDefaultGroups(group);
            }
        }
        catch (IOException e) {
            log.error("Could not read walk file " + configuration.getWalk().getAbsolutePath(), e);
        }
    }

    /**
     * Creates a list of {@link VariableBinding} out of a mapping of {@link OID} and {@link Variable}.
     *
     * @param variableBindings mapping of {@link OID} and {@link Variable}.
     * @param root             root SNMP OID.
     * @return list of {@link VariableBinding}.
     */
    private ArrayList<VariableBinding> generateSubtreeBindings(final SortedMap<OID, Variable> variableBindings, final OID root) {
        return variableBindings.entrySet().stream().filter(binding -> binding.getKey().size() >= root.size()).
                filter(binding -> binding.getKey().leftMostCompare(root.size(), root) == 0).
                map(binding -> new VariableBinding(binding.getKey(), binding.getValue())).collect(Collectors.toCollection(ArrayList::new));
    }

    /**
     * Registers a {@link ManagedObject} to the server with an empty {@link OctetString} community context.
     *
     * @param group {@link ManagedObject} to register.
     */
    private void registerDefaultGroups(final MOGroup group) {
        groups.add(group);
        registerGroupAndContext(group, new OctetString(""));
    }

    /**
     * Registers a {@link ManagedObject} to the server with a {@link OctetString} community context.
     *
     * @param group   {@link ManagedObject} to register.
     * @param context community context.
     */
    private void registerGroupAndContext(final MOGroup group, final OctetString context) {
        try {
            if (context == null || context.toString().equals("")) {
                MOContextScope contextScope = new DefaultMOContextScope(new OctetString(), group.getScope());
                ManagedObject other = server.lookup(new DefaultMOQuery(contextScope, false));
                if (other != null) {
                    log.warn("group {} already existed", group);
                    return;
                }

                contextScope = new DefaultMOContextScope(null, group.getScope());
                other = server.lookup(new DefaultMOQuery(contextScope, false));
                if (other != null) {
                    registerHard(group);
                    return;
                }
                this.server.register(group, new OctetString());
            } else {
                this.server.register(group, context);
            }
        } catch (final DuplicateRegistrationException e) {
            log.error("duplicate registrations are not allowed", e);
        }
    }

    /**
     * Sets the private registry value of {@link DefaultMOServer} via reflection.
     * FIXME
     * If there is any possibility to avoid this, then replace!
     *
     * @param group {@link ManagedObject} to register.
     */
    private void registerHard(final MOGroup group) {
        try {
            final Field registry = server.getClass().getDeclaredField("registry");
            registry.setAccessible(true);
            final SortedMap<MOScope, ManagedObject> reg = server.getRegistry();
            DefaultMOContextScope contextScope = new DefaultMOContextScope(new OctetString(""), group.getScope());
            reg.put(contextScope, group);
            registry.set(server, reg);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            log.warn("could not set server registry", e);
        }
    }

    /**
     * Unregisters all default managed objects in the specified context {@code ctx}.
     *
     * @param ctx the context from which all default managed objects should be unregistred
     */
    private void unregisterDefaultManagedObjects(final OctetString ctx) {
        final OID startOID = new OID(".1");
        final DefaultMOContextScope hackScope = new DefaultMOContextScope(ctx, startOID, true, startOID.nextPeer(), false);
        ManagedObject query;
        while ((query = server.lookup(new DefaultMOQuery(hackScope, false))) != null) {
            server.unregister(query, ctx);
        }
    }

    /**
     * Returns the variable bindings for a device configuration and a list of bindings.
     * <p>
     * In this step the {@link ModifiedVariable} instances will be created as a wrapper for dynamic variables.
     *
     * @param device   the device configuration
     * @param bindings the bindings as the base
     * @return the variable bindings for the specified device configuration
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private SortedMap<OID, Variable> getVariableBindings(final Device device, final Map<OID, Variable> bindings, final OctetString context) {
        log.trace("get variable bindings for agent \"{}\"", configuration.getName());
        final SortedMap<OID, Variable> result = new TreeMap<>();
        for (final Map.Entry<OID, Variable> binding : bindings.entrySet()) {
            final List<VariableModifier> modifiers;

            modifiers = device.getModifiers().stream().filter(modifier -> modifier.isApplicable(binding.getKey())).collect(Collectors.toList());

            if (modifiers.isEmpty()) {
                result.put(binding.getKey(), binding.getValue());
            } else {
                log.trace("created modified variable for OID {}", binding.getKey());
                try {
                    if (modifiers.stream().filter(m -> m instanceof Modifier).map(m -> (Modifier) m).anyMatch(m -> m.getModifier() instanceof CommunityContextModifier)) {
                        final List<CommunityContextModifier> contextModifiers = modifiers.stream().filter(m -> m instanceof Modifier).map(m -> (Modifier) m).filter(m -> m.getModifier() instanceof CommunityContextModifier).map(m -> (CommunityContextModifier) m.getModifier()).collect(Collectors.toList());
                        for (final CommunityContextModifier contextModifier : contextModifiers) {
                            result.putAll(contextModifier.getVariableBindings(context, binding.getKey()));
                        }
                    } else {
                        result.put(binding.getKey(), new ModifiedVariable(binding.getValue(), modifiers));
                    }
                } catch (final ClassCastException e) {
                    log.error("could not create variable binding for " + binding.getKey().toString() + " and file " + configuration.getWalk().getAbsolutePath(), e);
                }
            }

        }
        return result;
    }

    @Override
    protected void unregisterManagedObjects() {
        log.trace("unregistered managed objects for agent \"{}\"", agent);
        for (final ManagedObject mo : groups) {
            server.unregister(mo, null);
        }
    }

    @Override
    protected void addUsmUser(final USM usm) {
        log.trace("adding usm user {} for agent \"{}\"", usm.toString(), configuration.getName());
        // do nothing here
    }

    @Override
    protected void addNotificationTargets(final SnmpTargetMIB snmpTargetMIB, final SnmpNotificationMIB snmpNotificationMIB) {
        log.trace("adding notification targets {}, {} for agent \"{}\"", snmpTargetMIB.toString(), snmpNotificationMIB.toString(), configuration.getName());
        // do nothing here
    }

    @Override
    protected void addViews(final VacmMIB vacmMIB) {
        log.trace("adding views in the vacm MIB {} for agent \"{}\"", vacmMIB.toString(), configuration.getName());
        vacmMIB.addGroup(SecurityModel.SECURITY_MODEL_SNMPv1, new OctetString(configuration.getCommunity()), new OctetString("v1v2group"), StorageType.nonVolatile);
        vacmMIB.addGroup(SecurityModel.SECURITY_MODEL_SNMPv2c, new OctetString(configuration.getCommunity()), new OctetString("v1v2group"), StorageType.nonVolatile);
        vacmMIB.addGroup(SecurityModel.SECURITY_MODEL_USM, new OctetString("SHADES"), new OctetString("v3group"), StorageType.nonVolatile);
        vacmMIB.addGroup(SecurityModel.SECURITY_MODEL_USM, new OctetString("TEST"), new OctetString("v3test"), StorageType.nonVolatile);
        vacmMIB.addGroup(SecurityModel.SECURITY_MODEL_USM, new OctetString("SHA"), new OctetString("v3restricted"), StorageType.nonVolatile);
        vacmMIB.addGroup(SecurityModel.SECURITY_MODEL_USM, new OctetString("v3notify"), new OctetString("v3restricted"), StorageType.nonVolatile);

        // configure community index contexts
        for (final Long vlan : configuration.getDevice().getVlans()) {
            vacmMIB.addGroup(SecurityModel.SECURITY_MODEL_SNMPv1, new OctetString(configuration.getCommunity() + "@" + vlan), new OctetString("v1v2group"), StorageType.nonVolatile);
            vacmMIB.addGroup(SecurityModel.SECURITY_MODEL_SNMPv2c, new OctetString(configuration.getCommunity() + "@" + vlan), new OctetString("v1v2group"), StorageType.nonVolatile);
            vacmMIB.addAccess(new OctetString("v1v2group"), new OctetString(String.valueOf(vlan)), SecurityModel.SECURITY_MODEL_ANY, SecurityLevel.NOAUTH_NOPRIV, MutableVACM.VACM_MATCH_EXACT, new OctetString("fullReadView"), new OctetString("fullWriteView"), new OctetString("fullNotifyView"), StorageType.nonVolatile);
        }

        vacmMIB.addAccess(new OctetString("v1v2group"), new OctetString(), SecurityModel.SECURITY_MODEL_ANY, SecurityLevel.NOAUTH_NOPRIV, MutableVACM.VACM_MATCH_EXACT, new OctetString("fullReadView"), new OctetString("fullWriteView"), new OctetString("fullNotifyView"), StorageType.nonVolatile);
        vacmMIB.addAccess(new OctetString("v3group"), new OctetString(), SecurityModel.SECURITY_MODEL_USM, SecurityLevel.AUTH_PRIV, MutableVACM.VACM_MATCH_EXACT, new OctetString("fullReadView"), new OctetString("fullWriteView"), new OctetString("fullNotifyView"), StorageType.nonVolatile);
        vacmMIB.addAccess(new OctetString("v3restricted"), new OctetString(), SecurityModel.SECURITY_MODEL_USM, SecurityLevel.NOAUTH_NOPRIV, MutableVACM.VACM_MATCH_EXACT, new OctetString("restrictedReadView"), new OctetString("restrictedWriteView"), new OctetString("restrictedNotifyView"), StorageType.nonVolatile);
        vacmMIB.addAccess(new OctetString("v3test"), new OctetString(), SecurityModel.SECURITY_MODEL_USM, SecurityLevel.AUTH_PRIV, MutableVACM.VACM_MATCH_EXACT, new OctetString("testReadView"), new OctetString("testWriteView"), new OctetString("testNotifyView"), StorageType.nonVolatile);

        vacmMIB.addViewTreeFamily(new OctetString("fullReadView"), new OID("1"), new OctetString(), VacmMIB.vacmViewIncluded, StorageType.nonVolatile);
        vacmMIB.addViewTreeFamily(new OctetString("fullWriteView"), new OID("1"), new OctetString(), VacmMIB.vacmViewIncluded, StorageType.nonVolatile);
        vacmMIB.addViewTreeFamily(new OctetString("fullNotifyView"), new OID("1"), new OctetString(), VacmMIB.vacmViewIncluded, StorageType.nonVolatile);

        vacmMIB.addViewTreeFamily(new OctetString("restrictedReadView"), new OID("1.3.6.1.2"), new OctetString(), VacmMIB.vacmViewIncluded, StorageType.nonVolatile);
        vacmMIB.addViewTreeFamily(new OctetString("restrictedWriteView"), new OID("1.3.6.1.2.1"), new OctetString(), VacmMIB.vacmViewIncluded, StorageType.nonVolatile);
        vacmMIB.addViewTreeFamily(new OctetString("restrictedNotifyView"), new OID("1.3.6.1.2"), new OctetString(), VacmMIB.vacmViewIncluded, StorageType.nonVolatile);
        vacmMIB.addViewTreeFamily(new OctetString("restrictedNotifyView"), new OID("1.3.6.1.6.3.1"), new OctetString(), VacmMIB.vacmViewIncluded, StorageType.nonVolatile);

        vacmMIB.addViewTreeFamily(new OctetString("testReadView"), new OID("1.3.6.1.2"), new OctetString(), VacmMIB.vacmViewIncluded, StorageType.nonVolatile);
        vacmMIB.addViewTreeFamily(new OctetString("testReadView"), new OID("1.3.6.1.2.1.1"), new OctetString(), VacmMIB.vacmViewExcluded, StorageType.nonVolatile);
        vacmMIB.addViewTreeFamily(new OctetString("testWriteView"), new OID("1.3.6.1.2.1"), new OctetString(), VacmMIB.vacmViewIncluded, StorageType.nonVolatile);
        vacmMIB.addViewTreeFamily(new OctetString("testNotifyView"), new OID("1.3.6.1.2"), new OctetString(), VacmMIB.vacmViewIncluded, StorageType.nonVolatile);
    }

    @Override
    protected void addCommunities(final SnmpCommunityMIB snmpCommunityMIB) {
        log.trace("adding communities {} for agent \"{}\"", snmpCommunityMIB.toString(), configuration.getName());
        // configure community index contexts
        for (final Long vlan : configuration.getDevice().getVlans()) {
            configureSnmpCommunity(snmpCommunityMIB, vlan);
        }
        configureSnmpCommunity(snmpCommunityMIB, null);
    }

    /**
     * Configures an SNMP community for a given SNMP community context.
     *
     * @param snmpCommunityMIB SNMP community.
     * @param context          SNMP community context.
     */
    private void configureSnmpCommunity(final SnmpCommunityMIB snmpCommunityMIB, final Long context) {
        String communityString;
        OctetString contextName;
        if (context != null) {
            communityString = configuration.getCommunity() + "@" + context;
            contextName = new OctetString(String.valueOf(context));
        } else {
            communityString = configuration.getCommunity();
            contextName = new OctetString();
        }
        final Variable[] com2sec = new Variable[]{
                new OctetString(communityString),       // community name
                new OctetString(communityString),       // security name
                getAgent().getContextEngineID(),        // local engine ID
                contextName,                            // default context name
                new OctetString(),                      // transport tag
                new Integer32(StorageType.readOnly),    // storage type
                new Integer32(RowStatus.active)         // row status
        };
        final SnmpCommunityMIB.SnmpCommunityEntryRow row = snmpCommunityMIB.getSnmpCommunityEntry().createRow(
                new OctetString(communityString + "2" + communityString).toSubIndex(true), com2sec);
        snmpCommunityMIB.getSnmpCommunityEntry().addRow(row);
    }
}
