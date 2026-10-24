package cz.cuni.mff.releasemanager;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cz.cuni.mff.releasemanager.platform.PlatformHandler;
import cz.cuni.mff.releasemanager.types.Asset;
import cz.cuni.mff.releasemanager.types.SearchResult;

class GithubClientTest {

    private GithubClient githubClient;
    private HttpClient mockHttpClient;
    private PlatformHandler mockPlatformHandler;

    @BeforeEach
    void setUp() throws Exception {
        githubClient = new GithubClient();

        // use reflection to set the private fields - replace with dependency injection framework
        mockHttpClient = mock(HttpClient.class);
        Field clientField = GithubClient.class.getDeclaredField("client");
        clientField.setAccessible(true);
        clientField.set(githubClient, mockHttpClient);

        mockPlatformHandler = mock(PlatformHandler.class);
        Field handlerField = GithubClient.class.getDeclaredField("platformHandler");
        handlerField.setAccessible(true);
        handlerField.set(githubClient, mockPlatformHandler);
    }

    @Test
    void searchRepoByName_returnsSearchResultOnSuccess() throws Exception {
        String repoName = "testrepo";
        String json = """
            {
              "items": [
                {
                  "full_name": "owner/testrepo",
                  "description": "Test repository"
                }
              ]
            }
            """;
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(json);
        when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                .thenReturn(mockResponse);

        Optional<SearchResult> result = githubClient.searchRepoByName(repoName);

        assertTrue(result.isPresent());
        assertEquals("owner/testrepo", result.get().items().get(0).fullName());
        assertEquals("Test repository", result.get().items().get(0).description());
    }

    @Test
    void getLatestReleaseAssets_returnsAssetsOnSuccess() throws Exception {
        String repoFullName = "owner/repo";
        String json = """
            {
              "assets": [
                { "name": "file1.exe", "url": "http://example.com/file1.exe" },
                { "name": "file2.msi", "url": "http://example.com/file2.msi" }
              ]
            }
            """;
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(json);
        when(mockPlatformHandler.getFormats()).thenReturn(new String[]{"exe", "msi"});
        when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                .thenReturn(mockResponse);

        List<Asset> assets = githubClient.getLatestReleaseAssets(repoFullName);

        assertEquals(2, assets.size());
        assertEquals("file1.exe", assets.get(0).name());
        assertEquals("file2.msi", assets.get(1).name());
        assertEquals("http://example.com/file1.exe", assets.get(0).url());
        assertEquals("http://example.com/file2.msi", assets.get(1).url());
    }

    @Test
    void getLatestReleaseAssets_returnsEmptyForInvalidRepoName() {
        List<Asset> assets = githubClient.getLatestReleaseAssets("invalidRepoName");
        assertTrue(assets.isEmpty());
    }

    @Test
    void getLatestReleaseAssets_ignoresChecksumAndSignatureFiles() throws Exception {
        String json = """
            {
              "assets": [
                { "name": "Example-1.0.AppImage", "url": "http://example.com/Example-1.0.AppImage" },
                { "name": "Example-1.0.AppImage.DIGEST", "url": "http://example.com/digest" },
                { "name": "Example-1.0.AppImage.sig", "url": "http://example.com/sig" },
                { "name": "Example-1.0.AppImage.zsync", "url": "http://example.com/zsync" }
              ]
            }
            """;
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(json);
        when(mockPlatformHandler.getFormats()).thenReturn(new String[] {".appimage"});
        when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
            .thenReturn(mockResponse);

        List<Asset> assets = githubClient.getLatestReleaseAssets("owner/repo");

        assertEquals(1, assets.size());
        assertEquals("Example-1.0.AppImage", assets.get(0).name());
    }

    @Test
    void searchRepoByName_urlEncodesQuery() throws Exception {
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("{\"items\":[]}");
        when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                .thenReturn(mockResponse);

        githubClient.searchRepoByName("two words & more");

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockHttpClient).send(requestCaptor.capture(), eq(HttpResponse.BodyHandlers.ofString()));
        assertEquals("q=two+words+%26+more&per_page=5", requestCaptor.getValue().uri().getRawQuery());
    }

    @Test
    void installAsset_doesNotInstallEmptyDownload() throws Exception {
        HttpResponse<InputStream> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(InputStream.nullInputStream());
        when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofInputStream())))
                .thenReturn(mockResponse);

        Path result = githubClient.installAsset(new Asset("http://example.com/file.exe", "file.exe"), "owner/repo");

        assertNull(result);
        verifyNoInteractions(mockPlatformHandler);
    }

    @Test
    void installAsset_closesDownloadStream() throws Exception {
        boolean[] closed = {false};
        InputStream stream = new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public void close() throws IOException {
                closed[0] = true;
                super.close();
            }
        };
        HttpResponse<InputStream> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(stream);
        when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofInputStream())))
                .thenReturn(mockResponse);
        when(mockPlatformHandler.install(any(Path.class), any())).thenReturn(Path.of("/tmp/installed"));

        Path result = githubClient.installAsset(new Asset("http://example.com/file.exe", "file.exe"), "owner/repo");

        assertNotNull(result);
        assertTrue(closed[0]);
        verify(mockPlatformHandler).install(any(Path.class), any());
    }

}