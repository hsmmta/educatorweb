package org.example.educatorweb.knowledgegraph.build.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "kg")
public class KgBuildProperties {

    private Sources sources = new Sources();
    private Build build = new Build();

    public Sources getSources() { return sources; }
    public void setSources(Sources sources) { this.sources = sources; }
    public Build getBuild() { return build; }
    public void setBuild(Build build) { this.build = build; }

    public static class Sources {
        private List<GitHubSource> github = List.of();
        private WebApi webApi = new WebApi();
        public List<GitHubSource> getGithub() { return github; }
        public void setGithub(List<GitHubSource> github) { this.github = github; }
        public WebApi getWebApi() { return webApi; }
        public void setWebApi(WebApi webApi) { this.webApi = webApi; }
    }

    public static class GitHubSource {
        private String url, name;
        private String type = "course";
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
    }

    public static class WebApi {
        private boolean enabled = false;
        private String provider = "tavily";
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
    }

    public static class Build {
        private String schedule = "0 0 3 * * SUN";
        private int batchSize = 5;
        public String getSchedule() { return schedule; }
        public void setSchedule(String schedule) { this.schedule = schedule; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    }
}
