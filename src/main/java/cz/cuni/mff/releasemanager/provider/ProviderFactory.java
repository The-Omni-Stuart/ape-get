package cz.cuni.mff.releasemanager.provider;

// builds the adapter for a configured provider instance
public final class ProviderFactory {

    private final GithubClient defaultGithub;

    public ProviderFactory() {
        this(new GithubClient());
    }

    public ProviderFactory(GithubClient defaultGithub) {
        this.defaultGithub = defaultGithub;
    }

    public ReleaseProvider create(ProviderConfig config) {
        return switch (config.providerType().orElseThrow()) {
            case GITHUB -> new GithubProvider(
                "github".equals(config.name())
                    ? defaultGithub
                    : new GithubClient(config.resolvedApi(), config.tokenEnv()),
                config);
            case GITEA -> new GiteaProvider(config);
            case GITLAB -> new GitLabProvider(config);
            case RNGIT -> new RngitProvider(config);
        };
    }
}
