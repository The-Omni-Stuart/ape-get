package cz.cuni.mff.releasemanager.provider;

// resolves a provider name against the configured instances
@FunctionalInterface
public interface ProviderResolver {
    ReleaseProvider resolve(String name);
}
