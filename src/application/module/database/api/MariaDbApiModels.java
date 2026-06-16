package application.module.database.api;

import java.util.ArrayList;
import java.util.List;

public class MariaDbApiModels {
    public static class MainVersionInfo {
        public String name;
        public List<SubVersionInfo> subVersions = new ArrayList<>();
    }

    public static class SubVersionInfo {
        public String name;
        public List<DownloadEntry> downloads = new ArrayList<>();
    }

    public static class DownloadEntry {
        public String os;
        public String arch;
        public String file;
    }
}