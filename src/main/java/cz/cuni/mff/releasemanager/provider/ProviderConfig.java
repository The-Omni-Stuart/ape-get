package cz.cuni.mff.releasemanager.provider;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

// one configured provider instance
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProviderConfig(
    @JsonIgnore String name,
    String type,
    String url,
    String api,
    @JsonProperty("token_env") String tokenEnv
) {
    public ProviderConfig(String type, String url, String api, String tokenEnv) {
        this(null, type, url, api, tokenEnv);
    }

    @JsonIgnore
    public Optional<ProviderType> providerType() {
        return ProviderType.from(type);
    }

    @JsonIgnore
    public boolean isValid() {
        if (name != null && !name.isBlank() && !validName(name)) {
            return false;
        }
        Optional<ProviderType> providerType = providerType();
        if (providerType.isEmpty()) {
            return false;
        }
        boolean rngit = providerType.get() == ProviderType.RNGIT;
        if (!(rngit ? isRns(url) : isHttps(url))) {
            return false;
        }
        if (tokenEnv != null && !tokenEnv.isBlank()
            && !tokenEnv.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return false;
        }
        return api == null || api.isBlank()
            || (rngit ? isRns(api) : isHttps(api));
    }

    @JsonIgnore
    public String resolvedApi() {
        if (api != null && !api.isBlank()) {
            return stripTrailingSlash(api);
        }
        String base = stripTrailingSlash(url);
        return switch (providerType().orElseThrow()) {
            case GITHUB -> base.equals("https://github.com")
                || base.equals("http://github.com")
                ? "https://api.github.com"
                : base + "/api/v3";
            case GITEA -> base + "/api/v1";
            case GITLAB -> base + "/api/v4";
            case RNGIT -> base;
        };
    }

    // only send the token to hosts that belong to this provider
    @JsonIgnore
    public boolean allowsTokenFor(String downloadUrl) {
        if (providerType().orElseThrow() == ProviderType.RNGIT) {
            return false;
        }
        String host = hostOf(downloadUrl);
        return host != null
            && (host.equals(hostOf(resolvedApi())) || host.equals(hostOf(url)));
    }

    private static String hostOf(String value) {
        try {
            String host = value == null ? null : URI.create(value).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @JsonIgnore
    public ProviderConfig named(String providerName) {
        return new ProviderConfig(
            providerName,
            type == null ? null : type.toLowerCase(Locale.ROOT),
            stripTrailingSlash(url),
            resolvedApi(),
            tokenEnv);
    }

    public static boolean validName(String name) {
        return name != null && name.matches("[a-z0-9][a-z0-9._-]*");
    }

    private static boolean isRns(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            return "rns".equalsIgnoreCase(uri.getScheme())
                && host != null
                && host.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
                && (uri.getPath() == null || uri.getPath().isBlank()
                    || "/".equals(uri.getPath()))
                && uri.getUserInfo() == null;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static boolean isHttps(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            URI uri = new URI(value);
            return "https".equalsIgnoreCase(uri.getScheme())
                && uri.getHost() != null
                && uri.getUserInfo() == null;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static String stripTrailingSlash(String value) {
        if (value == null) {
            return null;
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
