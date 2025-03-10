package net.corda.testing.driver;

import net.corda.cipher.suite.impl.CipherSchemeMetadataImpl;
import net.corda.crypto.cipher.suite.CipherSchemeMetadata;
import net.corda.crypto.cipher.suite.schemes.KeyScheme;
import net.corda.data.KeyValuePair;
import net.corda.testing.driver.node.EmbeddedNodeService;
import net.corda.v5.base.types.MemberX500Name;
import net.corda.v5.crypto.KeySchemeCodes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.security.InvalidAlgorithmParameterException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.spec.AlgorithmParameterSpec;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static java.util.Collections.emptyMap;
import static java.util.Collections.unmodifiableMap;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toUnmodifiableMap;
import static net.corda.testing.driver.node.EmbeddedNodeService.NOTARY_WORKER_TAG;

public final class DriverNodes {
    private static final String NON_VALIDATING_NOTARY_PROTOCOL = "com.r3.corda.notary.plugin.nonvalidating";
    private static final CipherSchemeMetadata SCHEME_METADATA = new CipherSchemeMetadataImpl();
    public static final String DEFAULT_SCHEME_NAME = KeySchemeCodes.ECDSA_SECP256R1_CODE_NAME;

    private final Set<MemberX500Name> members;
    private final Map<MemberX500Name, Set<Integer>> notaries;
    private String schemeName;

    @SafeVarargs
    @NotNull
    @Unmodifiable
    private static <T> Set<T> setOf(@NotNull T item, T... others) {
        Set<T> items = new LinkedHashSet<>();
        items.add(item);
        Collections.addAll(items, others);
        return Set.copyOf(items);
    }

    @NotNull
    @Unmodifiable
    private static Set<@NotNull Integer> setOf(int value, int @NotNull... otherValues) {
        final Set<Integer> items = new LinkedHashSet<>();
        items.add(value);
        for (int otherValue : otherValues) {
            items.add(otherValue);
        }
        return Set.copyOf(items);
    }

    @NotNull
    private static KeyPairGenerator getKeyPairGenerator(@NotNull KeyScheme keyScheme)
            throws InvalidAlgorithmParameterException, NoSuchAlgorithmException {
        final KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(
            keyScheme.getAlgorithmName(),
            SCHEME_METADATA.getProviders().get(keyScheme.getProviderName())
        );
        final AlgorithmParameterSpec algorithmSpec = keyScheme.getAlgSpec();
        if (algorithmSpec != null) {
            keyPairGenerator.initialize(algorithmSpec, SCHEME_METADATA.getSecureRandom());
        } else {
            final Integer keySize = keyScheme.getKeySize();
            if (keySize != null) {
                keyPairGenerator.initialize(keySize, SCHEME_METADATA.getSecureRandom());
            }
        }
        return keyPairGenerator;
    }

    @NotNull
    private static Map<@NotNull MemberX500Name, @NotNull KeyPair> createNetwork(
        @NotNull KeyPairGenerator keyPairGenerator,
        @NotNull Iterable<@NotNull MemberX500Name> members
    ) {
        final Map<MemberX500Name, KeyPair> map = new LinkedHashMap<>();
        for (MemberX500Name member : members) {
            map.put(member, keyPairGenerator.generateKeyPair());
        }
        return map;
    }

    public DriverNodes(@NotNull Set<@NotNull MemberX500Name> members) {
        requireNonNull(members, "members must not be null");
        for (MemberX500Name member: members) {
            checkCommonName(member);
        }
        this.members = members;
        notaries = new LinkedHashMap<>();
        schemeName = DEFAULT_SCHEME_NAME;
    }

    public DriverNodes(@NotNull MemberX500Name member, @NotNull MemberX500Name... members) {
        this(setOf(member, members));
    }

    @SuppressWarnings("unused")
    @NotNull
    public DriverNodes withSchemeName(@NotNull String schemeName) {
        requireNonNull(schemeName, "schemeName must not be null");
        this.schemeName = schemeName;
        return this;
    }

    // Only non-validating notaries are supported.
    @NotNull
    public DriverNodes withNotary(@NotNull MemberX500Name notary, int protocolVersion, int... otherVersions) {
        requireNonNull(notary, "notary must not be null");
        checkCommonName(notary);
        notaries.put(notary, setOf(protocolVersion, otherVersions));
        return this;
    }

    @FunctionalInterface
    private interface DriverConstructor<T extends AbstractDriver> {
        @NotNull
        T build(
            @NotNull Map<@NotNull MemberX500Name, @NotNull KeyPair> members,
            @NotNull Map<@NotNull MemberX500Name, @NotNull KeyPair> notaries,
            @NotNull Set<@NotNull KeyValuePair> groupParameters
        );
    }

    private static void checkCommonName(@NotNull MemberX500Name name) {
        final String commonName = name.getCommonName();
        if (commonName != null && commonName.contains(NOTARY_WORKER_TAG)) {
            throw new IllegalArgumentException("Common name '" + commonName + "' should not contain " + NOTARY_WORKER_TAG);
        }
    }

    private void checkNoOverlap(@NotNull Collection<MemberX500Name> candidates) {
        final Set<MemberX500Name> overlap = new HashSet<>(candidates);
        overlap.retainAll(members);
        if (!overlap.isEmpty()) {
            throw new IllegalArgumentException("Member(s) " + overlap + " cannot also be a Notary");
        }
    }

    @NotNull
    private <R extends AbstractDriver> R build(@NotNull DriverConstructor<R> constructor) {
        if (!notaries.isEmpty()) {
            checkNoOverlap(notaries.keySet());
        }

        final Map<MemberX500Name, MemberX500Name> notaryWorkers = notaries.keySet().stream()
            .collect(toUnmodifiableMap(Function.identity(), EmbeddedNodeService::toNotaryWorkerName));

        final KeyPairGenerator keyPairGenerator;
        try {
            final KeyScheme keyScheme = SCHEME_METADATA.findKeyScheme(schemeName);
            keyPairGenerator = getKeyPairGenerator(keyScheme);
        } catch (NoSuchAlgorithmException | InvalidAlgorithmParameterException e) {
            throw new AssertionError(e.getMessage(), e);
        }

        final Map<MemberX500Name, KeyPair> memberNetwork = members.isEmpty() ? emptyMap()
            : unmodifiableMap(createNetwork(keyPairGenerator, members));
        final Map<MemberX500Name, KeyPair> notaryNetwork = notaryWorkers.isEmpty() ? emptyMap()
            : unmodifiableMap(createNetwork(keyPairGenerator, notaryWorkers.values()));
        final Set<KeyValuePair> groupParameters = new GroupParametersBuilder(notaries, notaryWorkers, notaryNetwork).build();
        return constructor.build(memberNetwork, notaryNetwork, groupParameters);
    }

    @NotNull
    public AllTestsDriver forAllTests() {
        return build(AllTestsDriver::new);
    }

    @NotNull
    public EachTestDriver forEachTest() {
        return build(EachTestDriver::new);
    }

    /**
     *
     */
    private static final class GroupParametersBuilder {
        private final Map<MemberX500Name, Set<Integer>> notaryServices;
        private final Map<MemberX500Name, MemberX500Name> notaryWorkers;
        private final Map<MemberX500Name, KeyPair> notaryNetwork;
        private final Set<KeyValuePair> groupParameters;
        private int notaryIndex;

        GroupParametersBuilder(
            @NotNull Map<@NotNull MemberX500Name, @NotNull Set<@NotNull Integer>> notaryServices,
            @NotNull Map<@NotNull MemberX500Name, @NotNull MemberX500Name> notaryWorkers,
            @NotNull Map<@NotNull MemberX500Name, @NotNull KeyPair> notaryNetwork
        ) {
            this.notaryServices = notaryServices;
            this.notaryWorkers = notaryWorkers;
            this.notaryNetwork = notaryNetwork;
            groupParameters = new LinkedHashSet<>();
            notaryIndex = 0;
        }

        @NotNull
        private KeyValuePair createNotaryServiceName(@NotNull MemberX500Name name) {
            return new KeyValuePair(String.format("corda.notary.service.%d.name", notaryIndex), name.toString());
        }

        @NotNull
        private KeyValuePair createNotaryKey(@NotNull KeyPair keyPair) {
            return new KeyValuePair(
                String.format("corda.notary.service.%d.keys.0", notaryIndex),
                SCHEME_METADATA.encodeAsString(keyPair.getPublic())
            );
        }

        @NotNull
        private List<@NotNull KeyValuePair> createNotaryProtocol(@NotNull Set<@NotNull Integer> protocolVersions) {
            final List<KeyValuePair> result = new ArrayList<>();
            result.add(new KeyValuePair(
                String.format("corda.notary.service.%d.flow.protocol.name", notaryIndex),
                NON_VALIDATING_NOTARY_PROTOCOL
            ));
            int index = 0;
            for (int protocolVersion : protocolVersions) {
                result.add(new KeyValuePair(
                    String.format("corda.notary.service.%d.flow.protocol.version.%d", notaryIndex, index),
                    String.valueOf(protocolVersion)
                ));
                ++index;
            }
            return result;
        }

        @NotNull
        @Unmodifiable
        Set<@NotNull KeyValuePair> build() {
            for (Map.Entry<@NotNull MemberX500Name, @NotNull Set<@NotNull Integer>> notaryService : notaryServices.entrySet()) {
                final MemberX500Name notaryServiceName = notaryService.getKey();
                final MemberX500Name notaryWorkerName = notaryWorkers.get(notaryServiceName);
                groupParameters.add(createNotaryServiceName(notaryServiceName));
                groupParameters.add(createNotaryKey(notaryNetwork.get(notaryWorkerName)));
                groupParameters.addAll(createNotaryProtocol(notaryService.getValue()));
                ++notaryIndex;
            }
            return Set.copyOf(groupParameters);
        }
    }
}
