package com.conveyal.datatools.manager.controllers.api;

import com.conveyal.datatools.common.utils.Consts;
import com.conveyal.datatools.common.utils.SparkUtils;
import com.conveyal.datatools.manager.DataManager;
import com.conveyal.datatools.manager.auth.Auth0UserProfile;
import com.conveyal.datatools.manager.jobs.ProcessSingleFeedJob;
import com.conveyal.datatools.manager.models.FeedVersion;
import com.conveyal.datatools.manager.persistence.FeedStore;
import com.conveyal.datatools.manager.persistence.Persistence;
import com.conveyal.datatools.manager.utils.HashUtils;
import com.conveyal.datatools.manager.utils.json.JsonUtil;
import com.conveyal.gtfs.GTFSFeed;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;

import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static com.conveyal.datatools.common.utils.SparkUtils.formatJobMessage;
import static com.conveyal.datatools.common.utils.SparkUtils.copyRequestStreamIntoFile;
import static com.conveyal.datatools.common.utils.SparkUtils.logMessageAndHalt;
import static spark.Spark.get;
import static spark.Spark.post;

/**
 * This handles the GTFS+ specific HTTP endpoints, which allow for validating GTFS+ tables,
 * downloading GTFS+ files to a client for editing (for example), and uploading/publishing a GTFS+ zip as
 * (for example, one that has been edited) as a new feed version. Here is the workflow in sequence:
 * 
 * 1. User uploads feed version (with or without GTFS+ tables).
 * 2. User views validation to determine if errors need amending.
 * 3. User makes edits (in client) and uploads modified GTFS+.
 * 4. Once user is satisfied with edits. User publishes as new feed version.
 * 
 * Created by demory on 4/13/16.
 */
public class GtfsPlusController {

    public static final Logger LOG = LoggerFactory.getLogger(GtfsPlusController.class);

    private static FeedStore gtfsPlusStore = new FeedStore("gtfsplus");

    /**
     * Upload a GTFS+ file based on a specific feed version and replace (or create)
     * the file in the GTFS+ specific feed store.
     */
    private static Boolean uploadGtfsPlusFile (Request req, Response res) {
        String feedVersionId = req.params("versionid");
        File newGtfsFile = new File(gtfsPlusStore.getPathToFeed(feedVersionId));
        copyRequestStreamIntoFile(req, newGtfsFile);
        return true;
    }

    /**
     * Download a GTFS+ file for a specific feed version. If no edited GTFS+ file
     * has been uploaded for the feed version, the original feed version will be returned.
     */
    private static HttpServletResponse getGtfsPlusFile(Request req, Response res) {
        String feedVersionId = req.params("versionid");
        LOG.info("Downloading GTFS+ file for FeedVersion " + feedVersionId);

        // check for saved
        File file = gtfsPlusStore.getFeed(feedVersionId);
        if(file == null) {
            return getGtfsPlusFromGtfs(feedVersionId, req, res);
        }
        LOG.info("Returning updated GTFS+ data");
        return SparkUtils.downloadFile(file, file.getName() + ".zip", req, res);
    }

    /**
     * Download only the GTFS+ tables in a zip for a specific feed version.
     */
    private static HttpServletResponse getGtfsPlusFromGtfs(String feedVersionId, Request req, Response res) {
        LOG.info("Extracting GTFS+ data from main GTFS feed");
        FeedVersion version = Persistence.feedVersions.getById(feedVersionId);

        File gtfsPlusFile = null;

        // create a set of valid GTFS+ table names
        Set<String> gtfsPlusTables = new HashSet<>();
        for(int i = 0; i < DataManager.gtfsPlusConfig.size(); i++) {
            JsonNode tableNode = DataManager.gtfsPlusConfig.get(i);
            gtfsPlusTables.add(tableNode.get("name").asText());
        }

        try {
            // create a new zip file to only contain the GTFS+ tables
            gtfsPlusFile = File.createTempFile(version.id + "_gtfsplus", ".zip");
            ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(gtfsPlusFile));

            // iterate through the existing GTFS file, copying any GTFS+ tables
            ZipFile gtfsFile = new ZipFile(version.retrieveGtfsFile());
            final Enumeration<? extends ZipEntry> entries = gtfsFile.entries();
            byte[] buffer = new byte[512];
            while (entries.hasMoreElements()) {
                final ZipEntry entry = entries.nextElement();
                if(!gtfsPlusTables.contains(entry.getName())) continue;

                // create a new empty ZipEntry and copy the contents
                ZipEntry newEntry = new ZipEntry(entry.getName());
                zos.putNextEntry(newEntry);
                InputStream in = gtfsFile.getInputStream(entry);
                while (0 < in.available()){
                    int read = in.read(buffer);
                    zos.write(buffer,0,read);
                }
                in.close();
                zos.closeEntry();
            }
            zos.close();
        } catch (IOException e) {
            logMessageAndHalt(req, 500, "An error occurred while trying to create a gtfs file", e);
        }

        return SparkUtils.downloadFile(gtfsPlusFile, gtfsPlusFile.getName() + ".zip", req, res);
    }

    private static Long getGtfsPlusFileTimestamp(Request req, Response res) {
        String feedVersionId = req.params("versionid");

        // check for saved GTFS+ data
        File file = gtfsPlusStore.getFeed(feedVersionId);
        if (file == null) {
            FeedVersion feedVersion = Persistence.feedVersions.getById(feedVersionId);
            if (feedVersion != null) {
                file = feedVersion.retrieveGtfsFile();
            } else {
                logMessageAndHalt(req, 400, "Feed version ID is not valid");
            }
        }

        if (file != null) {
            return file.lastModified();
        } else {
            logMessageAndHalt(req, 404, "Feed version file not found");
            return null;
        }
    }

    /**
     * Publishes the edited/saved GTFS+ file as a new feed version for the feed source.
     * This is the final stage in the GTFS+ validation/editing workflow described in the
     * class's javadoc.
     */
    private static String publishGtfsPlusFile(Request req, Response res) {
        Auth0UserProfile profile = req.attribute("user");
        String feedVersionId = req.params("versionid");
        LOG.info("Publishing GTFS+ for " + feedVersionId);
        File plusFile = gtfsPlusStore.getFeed(feedVersionId);
        if(plusFile == null || !plusFile.exists()) {
            logMessageAndHalt(req, 400, "No saved GTFS+ data for version");
        }

        FeedVersion feedVersion = Persistence.feedVersions.getById(feedVersionId);

        // create a set of valid GTFS+ table names
        Set<String> gtfsPlusTables = new HashSet<>();
        for(int i = 0; i < DataManager.gtfsPlusConfig.size(); i++) {
            JsonNode tableNode = DataManager.gtfsPlusConfig.get(i);
            gtfsPlusTables.add(tableNode.get("name").asText());
        }

        File newFeed = null;

        try {
            // First, create a new zip file to only contain the GTFS+ tables
            newFeed = File.createTempFile(feedVersionId + "_new", ".zip");
            ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(newFeed));

            // Next, iterate through the existing GTFS file, copying all non-GTFS+ tables.
            ZipFile gtfsFile = new ZipFile(feedVersion.retrieveGtfsFile());
            final Enumeration<? extends ZipEntry> entries = gtfsFile.entries();
            byte[] buffer = new byte[512];
            while (entries.hasMoreElements()) {
                final ZipEntry entry = entries.nextElement();
                if(gtfsPlusTables.contains(entry.getName()) || entry.getName().startsWith("_")) continue; // skip GTFS+ and non-standard tables

                // create a new empty ZipEntry and copy the contents
                ZipEntry newEntry = new ZipEntry(entry.getName());
                zos.putNextEntry(newEntry);
                InputStream in = gtfsFile.getInputStream(entry);
                while (0 < in.available()){
                    int read = in.read(buffer);
                    zos.write(buffer,0,read);
                }
                in.close();
                zos.closeEntry();
            }

            // iterate through the GTFS+ file, copying all entries
            ZipFile plusZipFile = new ZipFile(plusFile);
            final Enumeration<? extends ZipEntry> plusEntries = plusZipFile.entries();
            while (plusEntries.hasMoreElements()) {
                final ZipEntry entry = plusEntries.nextElement();

                ZipEntry newEntry = new ZipEntry(entry.getName());
                zos.putNextEntry(newEntry);
                InputStream in = plusZipFile.getInputStream(entry);
                while (0 < in.available()){
                    int read = in.read(buffer);
                    zos.write(buffer,0,read);
                }
                in.close();
                zos.closeEntry();
            }
            zos.close();
        } catch (IOException e) {
            logMessageAndHalt(req, 500, "Error creating combined GTFS/GTFS+ file", e);
        }

        FeedVersion newFeedVersion = new FeedVersion(feedVersion.parentFeedSource());
        File newGtfsFile = null;
        try {
            newGtfsFile = newFeedVersion.newGtfsFile(new FileInputStream(newFeed));
        } catch (IOException e) {
            e.printStackTrace();
            logMessageAndHalt(req, 500, "Error reading GTFS file input stream", e);
        }
        if (newGtfsFile == null) {
            logMessageAndHalt(req, 500, "GTFS input file must not be null");
            return null;
        }
        newFeedVersion.fileSize = newGtfsFile.length();
        newFeedVersion.hash = HashUtils.hashFile(newGtfsFile);

        // Must be handled by executor because it takes a long time.
        ProcessSingleFeedJob processSingleFeedJob = new ProcessSingleFeedJob(newFeedVersion, profile.getUser_id(), true);
        DataManager.heavyExecutor.execute(processSingleFeedJob);

        return formatJobMessage(processSingleFeedJob.jobId, "Feed version is processing.");
    }

    /**
     * HTTP endpoint that validates GTFS+ tables for a specific feed version (or its saved/edited GTFS+).
     * FIXME: For now this uses the MapDB-backed GTFSFeed class. Which actually suggests that this might
     * should be contained within a MonitorableJob.
     */
    private static Collection<ValidationIssue> getGtfsPlusValidation(Request req, Response res) {
        String feedVersionId = req.params("versionid");
        LOG.info("Validating GTFS+ for " + feedVersionId);
        FeedVersion feedVersion = Persistence.feedVersions.getById(feedVersionId);

        List<ValidationIssue> issues = new LinkedList<>();


        // load the main GTFS
        // FIXME: Swap MapDB-backed GTFSFeed for use of SQL data?
        GTFSFeed gtfsFeed = GTFSFeed.fromFile(feedVersion.retrieveGtfsFile().getAbsolutePath());
        // check for saved GTFS+ data
        File file = gtfsPlusStore.getFeed(feedVersionId);
        if (file == null) {
            LOG.warn("GTFS+ file not found, loading from main version GTFS.");
            file = feedVersion.retrieveGtfsFile();
        }
        int gtfsPlusTableCount = 0;
        try {
            ZipFile zipFile = new ZipFile(file);
            final Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                final ZipEntry entry = entries.nextElement();
                for(int i = 0; i < DataManager.gtfsPlusConfig.size(); i++) {
                    JsonNode tableNode = DataManager.gtfsPlusConfig.get(i);
                    if(tableNode.get("name").asText().equals(entry.getName())) {
                        LOG.info("Validating GTFS+ table: " + entry.getName());
                        gtfsPlusTableCount++;
                        validateTable(issues, tableNode, zipFile.getInputStream(entry), gtfsFeed);
                    }
                }
            }

        } catch(IOException e) {
            logMessageAndHalt(req, 500, "Could not read GTFS+ zip file", e);
        }
        LOG.info("GTFS+ tables found: {}/{}", gtfsPlusTableCount, DataManager.gtfsPlusConfig.size());
        return issues;
    }

    /**
     * Validate a single GTFS+ table using the table specification found in gtfsplus.yml.
     */
    private static void validateTable(
        Collection<ValidationIssue> issues,
        JsonNode tableNode,
        InputStream inputStream,
        GTFSFeed gtfsFeed
    ) throws IOException {
        String tableId = tableNode.get("id").asText();
        BufferedReader in = new BufferedReader(new InputStreamReader(inputStream));
        String line = in.readLine();
        String[] fields = line.split(",");
        List<String> fieldList = Arrays.asList(fields);
        JsonNode[] fieldNodes = new JsonNode[fields.length];
        JsonNode fieldsNode = tableNode.get("fields");
        for(int i = 0; i < fieldsNode.size(); i++) {
            JsonNode fieldNode = fieldsNode.get(i);
            int index = fieldList.indexOf(fieldNode.get("name").asText());
            if(index != -1) fieldNodes[index] = fieldNode;
        }

        int rowIndex = 0;
        while((line = in.readLine()) != null) {
            String[] values = line.split(Consts.COLUMN_SPLIT, -1);
            for(int v=0; v < values.length; v++) {
                validateTableValue(issues, tableId, rowIndex, values[v], fieldNodes[v], gtfsFeed);
            }
            rowIndex++;
        }
    }

    private static void validateTableValue(Collection<ValidationIssue> issues, String tableId, int rowIndex, String value, JsonNode fieldNode, GTFSFeed gtfsFeed) {
        if(fieldNode == null) return;
        String fieldName = fieldNode.get("name").asText();

        if(fieldNode.get("required") != null && fieldNode.get("required").asBoolean()) {
            if(value == null || value.length() == 0) {
                issues.add(new ValidationIssue(tableId, fieldName, rowIndex, "Required field missing value"));
            }
        }

        switch(fieldNode.get("inputType").asText()) {
            case "DROPDOWN":
                boolean invalid = true;
                ArrayNode options = (ArrayNode) fieldNode.get("options");
                for (JsonNode option : options) {
                    String optionValue = option.get("value").asText();

                    // NOTE: per client's request, this check has been made case insensitive
                    boolean valuesAreEqual = optionValue.equalsIgnoreCase(value);

                    // if value is found in list of options, break out of loop
                    if (valuesAreEqual || (!fieldNode.get("required").asBoolean() && value.equals(""))) {
                        invalid = false;
                        break;
                    }
                }
                if (invalid) {
                    issues.add(new ValidationIssue(tableId, fieldName, rowIndex, "Value: " + value + " is not a valid option."));
                }
                break;
            case "TEXT":
                // check if value exceeds max length requirement
                if(fieldNode.get("maxLength") != null) {
                    int maxLength = fieldNode.get("maxLength").asInt();
                    if(value.length() > maxLength) {
                        issues.add(new ValidationIssue(tableId, fieldName, rowIndex, "Text value exceeds the max. length of "+maxLength));
                    }
                }
                break;
            case "GTFS_ROUTE":
                if(!gtfsFeed.routes.containsKey(value)) {
                    issues.add(new ValidationIssue(tableId, fieldName, rowIndex, "Route ID "+ value + " not found in GTFS"));
                }
                break;
            case "GTFS_STOP":
                if(!gtfsFeed.stops.containsKey(value)) {
                    issues.add(new ValidationIssue(tableId, fieldName, rowIndex, "Stop ID "+ value + " not found in GTFS"));
                }
                break;
            case "GTFS_TRIP":
                if(!gtfsFeed.trips.containsKey(value)) {
                    issues.add(new ValidationIssue(tableId, fieldName, rowIndex, "Trip ID "+ value + " not found in GTFS"));
                }
                break;
            case "GTFS_FARE":
                if(!gtfsFeed.fares.containsKey(value)) {
                    issues.add(new ValidationIssue(tableId, fieldName, rowIndex, "Fare ID "+ value + " not found in GTFS"));
                }
                break;
            case "GTFS_SERVICE":
                if(!gtfsFeed.services.containsKey(value)) {
                    issues.add(new ValidationIssue(tableId, fieldName, rowIndex, "Service ID "+ value + " not found in GTFS"));
                }
                break;
        }

    }

    public static class ValidationIssue implements Serializable {
        private static final long serialVersionUID = 1L;
        public String tableId;
        public String fieldName;
        public int rowIndex;
        public String description;

        public ValidationIssue(String tableId, String fieldName, int rowIndex, String description) {
            this.tableId = tableId;
            this.fieldName = fieldName;
            this.rowIndex = rowIndex;
            this.description = description;
        }
    }

    public static void register(String apiPrefix) {
        post(apiPrefix + "secure/gtfsplus/:versionid", GtfsPlusController::uploadGtfsPlusFile, JsonUtil.objectMapper::writeValueAsString);
        get(apiPrefix + "secure/gtfsplus/:versionid", GtfsPlusController::getGtfsPlusFile);
        get(apiPrefix + "secure/gtfsplus/:versionid/timestamp", GtfsPlusController::getGtfsPlusFileTimestamp, JsonUtil.objectMapper::writeValueAsString);
        get(apiPrefix + "secure/gtfsplus/:versionid/validation", GtfsPlusController::getGtfsPlusValidation, JsonUtil.objectMapper::writeValueAsString);
        post(apiPrefix + "secure/gtfsplus/:versionid/publish", GtfsPlusController::publishGtfsPlusFile, JsonUtil.objectMapper::writeValueAsString);
    }
}
