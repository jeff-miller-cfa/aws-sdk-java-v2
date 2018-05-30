/*
 * Copyright 2010-2018 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.awscore.client.builder;

import static software.amazon.awssdk.awscore.config.AwsAdvancedClientOption.ENABLE_DEFAULT_REGION_DETECTION;

import java.net.URI;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import software.amazon.awssdk.annotations.SdkProtectedApi;
import software.amazon.awssdk.annotations.SdkTestInternalApi;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.awscore.config.AwsAdvancedClientOption;
import software.amazon.awssdk.awscore.config.AwsImmutableAsyncClientConfiguration;
import software.amazon.awssdk.awscore.config.AwsImmutableSyncClientConfiguration;
import software.amazon.awssdk.awscore.config.AwsMutableClientConfiguration;
import software.amazon.awssdk.awscore.config.defaults.AwsClientConfigurationDefaults;
import software.amazon.awssdk.awscore.config.defaults.AwsGlobalClientConfigurationDefaults;
import software.amazon.awssdk.awscore.internal.EndpointUtils;
import software.amazon.awssdk.core.client.builder.ExecutorProvider;
import software.amazon.awssdk.core.client.builder.SdkDefaultClientBuilder;
import software.amazon.awssdk.core.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.ServiceMetadata;
import software.amazon.awssdk.regions.providers.AwsRegionProvider;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;

/**
 * An SDK-internal implementation of the methods in {@link AwsClientBuilder}, {@link AwsAsyncClientBuilder} and
 * {@link AwsSyncClientBuilder}. This implements all methods required by those interfaces, allowing service-specific builders to
 * just
 * implement the configuration they wish to add.
 *
 * <p>By implementing both the sync and async interface's methods, service-specific builders can share code between their sync
 * and
 * async variants without needing one to extend the other. Note: This only defines the methods in the sync and async builder
 * interfaces. It does not implement the interfaces themselves. This is because the sync and async client builder interfaces both
 * require a type-constrained parameter for use in fluent chaining, and a generic type parameter conflict is introduced into the
 * class hierarchy by this interface extending the builder interfaces themselves.</p>
 *
 * <p>Like all {@link AwsClientBuilder}s, this class is not thread safe.</p>
 *
 * @param <B> The type of builder, for chaining.
 * @param <C> The type of client generated by this builder.
 */
@SdkProtectedApi
public abstract class AwsDefaultClientBuilder<B extends AwsClientBuilder<B, C>, C> extends SdkDefaultClientBuilder<B, C>
    implements AwsClientBuilder<B, C> {
    private static final String DEFAULT_ENDPOINT_PROTOCOL = "https";
    private static final AwsRegionProvider DEFAULT_REGION_PROVIDER = new DefaultAwsRegionProviderChain();

    private AwsMutableClientConfiguration awsMutableClientConfiguration = new AwsMutableClientConfiguration();

    private Region region;

    protected AwsDefaultClientBuilder() {
        super();
    }

    @SdkTestInternalApi
    AwsDefaultClientBuilder(SdkHttpClient.Builder defaultHttpClientBuilder,
                            SdkAsyncHttpClient.Builder defaultAsyncHttpClientFactory) {
        super(defaultHttpClientBuilder, defaultAsyncHttpClientFactory);
    }

    /**
     * Implemented by child classes to define the endpoint prefix used when communicating with AWS. This constitutes the first
     * part of the URL in the DNS name for the service. Eg. in the endpoint "dynamodb.amazonaws.com", this is the "dynamodb".
     *
     * <p>For standard services, this should match the "endpointPrefix" field in the AWS model.</p>
     */
    protected abstract String serviceEndpointPrefix();

    protected abstract String signingName();

    /**
     * An optional hook that can be overridden by service client builders to set service-specific defaults.
     *
     * @return The service defaults that should be applied.
     */
    @Override
    protected AwsClientConfigurationDefaults serviceDefaults() {
        return new AwsClientConfigurationDefaults() {
        };
    }

    /**
     * Used by child classes to get the signing region configured on this builder. This is usually used when generating the child
     * class's signer. This will never return null.
     */
    protected final Region signingRegion() {
        return ServiceMetadata.of(serviceEndpointPrefix()).signingRegion(
            resolveRegion().orElseThrow(() -> new IllegalStateException("The signing region could not be determined.")));
    }

    /**
     * Return a sync client configuration object, populated with the following chain of priorities.
     * <ol>
     * <li>Customer Configuration</li>
     * <li>Builder-Specific Default Configuration</li>
     * <li>Service-Specific Default Configuration</li>
     * <li>Global Default Configuration</li>
     * </ol>
     */
    @Override
    protected final AwsImmutableSyncClientConfiguration syncClientConfiguration() {
        AwsMutableClientConfiguration configuration = awsMutableClientConfiguration.clone();
        builderDefaults().applySyncDefaults(configuration);
        serviceDefaults().applySyncDefaults(configuration);
        new AwsGlobalClientConfigurationDefaults().applySyncDefaults(configuration);
        applySdkHttpClient(configuration);
        return new AwsImmutableSyncClientConfiguration(configuration);
    }

    /**
     * Return an async client configuration object, populated with the following chain of priorities.
     * <ol>
     * <li>Customer Configuration</li>
     * <li>Builder-Specific Default Configuration</li>
     * <li>Service-Specific Default Configuration</li>
     * <li>Global Default Configuration</li>
     * </ol>
     */
    @Override
    protected final AwsImmutableAsyncClientConfiguration asyncClientConfiguration() {
        AwsMutableClientConfiguration configuration = awsMutableClientConfiguration.clone();
        builderDefaults().applyAsyncDefaults(configuration);
        serviceDefaults().applyAsyncDefaults(configuration);
        new AwsGlobalClientConfigurationDefaults().applyAsyncDefaults(configuration);
        applySdkAsyncHttpClient(configuration);
        return new AwsImmutableAsyncClientConfiguration(configuration);
    }

    /**
     * Add builder-specific configuration on top of the customer-defined configuration, if needed. Specifically, if the customer
     * has specified a region in place of an endpoint, this will determine the endpoint to be used for AWS communication.
     */
    @Override
    protected final AwsClientConfigurationDefaults builderDefaults() {
        return new AwsClientConfigurationDefaults() {
            /**
             * If the customer did not specify an endpoint themselves, attempt to generate one automatically.
             */
            @Override
            protected URI getEndpointDefault() {
                return resolveEndpoint().orElse(null);
            }

            /**
             * If the customer did not specify a region provider themselves, use the default chain.
             */
            @Override
            protected AwsCredentialsProvider getCredentialsDefault() {
                return DefaultCredentialsProvider.create();
            }

            /**
             * Create the async executor service that should be used for async client executions.
             */
            @Override
            protected ScheduledExecutorService getAsyncExecutorDefault() {
                return Optional.ofNullable(asyncExecutorProvider).map(ExecutorProvider::get).orElse(null);
            }

            @Override
            protected void applyOverrideDefaults(ClientOverrideConfiguration.Builder builder) {
                builder.advancedOption(AwsAdvancedClientOption.AWS_REGION,
                                       resolveRegion().orElseThrow(() -> new SdkClientException("AWS region not provided")));
                builder.advancedOption(AwsAdvancedClientOption.SERVICE_SIGNING_NAME, signingName());
                builder.advancedOption(AwsAdvancedClientOption.SIGNING_REGION, signingRegion());
            }
        };
    }

    /**
     * Resolve the service endpoint that should be used based on the customer's configuration.
     */
    @Override
    protected Optional<URI> resolveEndpoint() {
        URI configuredEndpoint = awsMutableClientConfiguration.endpoint();
        return configuredEndpoint != null ? Optional.of(configuredEndpoint) : endpointFromRegion();
    }

    /**
     * Load the region from the default region provider if enabled.
     */
    private Optional<Region> regionFromDefaultProvider() {
        return useRegionProviderChain() ? Optional.ofNullable(DEFAULT_REGION_PROVIDER.getRegion()) : Optional.empty();
    }

    /**
     * Determine whether loading the region from the region provider chain is allowed by the options. True by default.
     */
    private boolean useRegionProviderChain() {
        Boolean configuredToUseRegionProviderChain;
        configuredToUseRegionProviderChain = awsMutableClientConfiguration.overrideConfiguration()
                                                                          .advancedOption(ENABLE_DEFAULT_REGION_DETECTION);
        return configuredToUseRegionProviderChain != null ? configuredToUseRegionProviderChain : true;
    }

    @Override
    public B endpointOverride(URI endpointOverride) {
        awsMutableClientConfiguration.endpoint(endpointOverride);
        return thisBuilder();
    }

    @Override
    public final B overrideConfiguration(ClientOverrideConfiguration overrideConfiguration) {
        awsMutableClientConfiguration.overrideConfiguration(overrideConfiguration);
        return thisBuilder();
    }

    /**
     * Resolve the region that should be used based on the customer's configuration.
     */
    private Optional<Region> resolveRegion() {
        return region != null ? Optional.of(region) : regionFromDefaultProvider();
    }

    /**
     * Load the endpoint from the resolved region.
     */
    private Optional<URI> endpointFromRegion() {
        return resolveRegion().map(r -> EndpointUtils.buildEndpoint(DEFAULT_ENDPOINT_PROTOCOL, serviceEndpointPrefix(), r));
    }

    @Override
    public final B region(Region region) {
        this.region = region;
        return thisBuilder();
    }

    public final void setRegion(Region region) {
        region(region);
    }

    @Override
    public final B credentialsProvider(AwsCredentialsProvider credentialsProvider) {
        awsMutableClientConfiguration.credentialsProvider(credentialsProvider);
        return thisBuilder();
    }

    public final void setCredentialsProvider(AwsCredentialsProvider credentialsProvider) {
        credentialsProvider(credentialsProvider);
    }
}
