package pawriro.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

final class DestinationSearch {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private static final Semaphore REQUESTS = new Semaphore(3);

    static Object search(JsonNode input) {
        String query = input.path("query").asText("").strip();
        if (!input.path("query").isTextual() || query.length()<2 || query.length()>120)
            throw new Api.Problem(422,"Enter a query of 2 to 120 characters");
        String key = System.getenv("ORS_API_KEY");
        if (key==null || key.isBlank()) throw new Api.Problem(503,"ORS geocoding key unavailable");
        if (!REQUESTS.tryAcquire()) throw new Api.Problem(429,"Destination search busy");
        try {
            String base = System.getenv().getOrDefault("ORS_GEOCODE_URL","https://api.openrouteservice.org/geocode/search");
            var request = HttpRequest.newBuilder(URI.create(base+"?size=8&boundary.country=KR&text="+
                URLEncoder.encode(query,StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(4)).header("Authorization",key).GET().build();
            var response = HTTP.send(request,HttpResponse.BodyHandlers.ofString());
            if (response.statusCode()!=200) throw new Api.Problem(503,"ORS geocoding unavailable");
            List<Map<String,Object>> results = new ArrayList<>();
            var seen = new HashSet<List<JsonNode>>();
            for (JsonNode feature : JSON.readTree(response.body()).path("features")) {
                var point = feature.path("geometry").path("coordinates");
                var properties = feature.path("properties");
                String label = properties.path("label").asText("").strip();
                String name = properties.path("name").asText(label).strip();
                if (!point.isArray() || point.size()<2 || !point.get(0).isNumber() || !point.get(1).isNumber()
                    || !(Math.abs(point.get(0).asDouble())<=180) || !(Math.abs(point.get(1).asDouble())<=90)
                    || name.isEmpty() || !seen.add(List.of(point.get(0),point.get(1)))) continue;
                results.add(Map.of("name",name,"address",label,"longitude",point.get(0),"latitude",point.get(1)));
                if (results.size()==8) break;
            }
            return results;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new Api.Problem(503,"Destination search interrupted");
        } catch (IOException failure) {
            throw new Api.Problem(503,"ORS geocoding unavailable");
        } finally {
            REQUESTS.release();
        }
    }

    private DestinationSearch() {}
}
