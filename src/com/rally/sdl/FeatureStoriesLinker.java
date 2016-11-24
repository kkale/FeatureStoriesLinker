package com.rally.sdl;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.logging.Logger;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.logging.impl.Log4JLogger;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rallydev.rest.RallyRestApi;
import com.rallydev.rest.request.QueryRequest;
import com.rallydev.rest.request.UpdateRequest;
import com.rallydev.rest.response.QueryResponse;
import com.rallydev.rest.response.UpdateResponse;
import com.rallydev.rest.util.Fetch;
import com.rallydev.rest.util.QueryFilter;

public class FeatureStoriesLinker {
	// static Log4JLogger logger = new Log4JLogger();
	static Logger logger = Logger.getLogger("RALLY LOGGER");

	static RallyRestApi restApi;

	public static void main(String[] args) {
		Options options = new Options();

		Option workspace = new Option("ws", "workspace", true, "workspace name");
		workspace.setRequired(true);
		options.addOption(workspace);

		Option project = new Option("pr", "project", true, "project name");
		project.setRequired(true);
		options.addOption(project);

		Option key = new Option("key", "apikey", true, "api key");
		key.setRequired(true);
		options.addOption(key);

		Option csvFile = new Option("csv", "csvfile", true, "csv file");
		csvFile.setRequired(true);
		options.addOption(csvFile);

		CommandLineParser parser = new DefaultParser();
		HelpFormatter formatter = new HelpFormatter();
		CommandLine cmd = null;

		try {
			cmd = parser.parse(options, args);
		} catch (ParseException e) {
			System.out.println(e.getMessage());
			formatter.printHelp("utility-name", options);
			System.exit(1);
		}
		String workspaceName = cmd.getOptionValue("workspace");
		String projectName = cmd.getOptionValue("project");
		String apiKey = cmd.getOptionValue("apikey");
		String csv = cmd.getOptionValue("csvfile");

		String workspaceID = null;
		String subID = null;

		FeatureStoriesLinker linker = new FeatureStoriesLinker();

		try {
			restApi = RestApiFactory.getRestApi(apiKey);
			workspaceID = linker.getRefFromContainerName("workspace", workspaceName);
			subID = linker.getRefFromContainerName("project", projectName);
		} catch (IOException | URISyntaxException e1) {
			logger.info("Could not connect to Rally" + e1.getMessage());
		}

		ArrayList<String[]> storyFeaturePairs = linker.getStoryFeaturePairs(csv);

		try {
			for (String[] storyFeaturePair : storyFeaturePairs) {
				logger.info(String.format("Linking story %s to feature %s", storyFeaturePair[0],
						storyFeaturePair[1]));
				linker.link(storyFeaturePair[0], storyFeaturePair[1], workspaceID, subID);
			}
		} catch (IOException e) {
			logger.info("encountered exception: " + e.getMessage());
			System.exit(404);
		}
	}

	private ArrayList<String[]> getStoryFeaturePairs(String csvFile) {
		BufferedReader csvReader = null;
		try {
			csvReader = new BufferedReader(new FileReader(new File(csvFile)));
		} catch (FileNotFoundException e) {
			logger.info("Could not find the csv file: " + csvFile);
			logger.info(e.getMessage());
			System.exit(404);
		}
		String featureStoryLine = "";
		ArrayList<String[]> featureStoryPairs = new ArrayList<String[]>();
		try {
			while ((featureStoryLine = csvReader.readLine()) != null) {
				String[] storyFeaturePair = featureStoryLine.split(",");
				if (storyFeaturePair.length < 2) {
					logger.info(String.format("found incomplete pair %s", storyFeaturePair[0]));
					continue;
				}
				featureStoryPairs.add(storyFeaturePair);
			}
		} catch (IOException e) {
			logger.info("Could not read the csv file: " + e.getMessage());
		} finally {
			try {
				csvReader.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		return featureStoryPairs;
	}

	private void link(String storyJiraID, String featureJiraID, String workspaceID, String projectID)
			throws IOException {
		JsonObject story = getWorkItemFromJiraID(storyJiraID, "hierarchicalrequirement", workspaceID, projectID);
		JsonObject feature = getWorkItemFromJiraID(featureJiraID, "PortfolioItem/Feature", workspaceID, projectID);

		if (story == null || feature == null) {
			// Abort if no link could be made
			return;
		}

		JsonObject updatedStory = new JsonObject();
		updatedStory.add("portfolioitem", feature.get("_ref"));
		UpdateRequest updateRequest = new UpdateRequest(story.get("_ref").getAsString(),
				updatedStory);
		logger.info("updatedrequest: " + updateRequest.toUrl());
		UpdateResponse updateResponse = restApi.update(updateRequest);
		JsonObject obj = updateResponse.getObject();

		if (obj == null) {
			logger.info(String.format("Could not link %s with %s: ", storyJiraID, featureJiraID));
			for (String error : updateResponse.getErrors()) {
				logger.info(String.format("error: %s", error));
			}
		}

		for (String error : updateResponse.getWarnings()) {
			logger.info(String.format("warning: %s", error));
		}

	}

	private JsonObject getWorkItemFromJiraID(String storyJiraID, String type, String workspaceID,
			String projectID) throws IOException {
		QueryRequest workItemQuery = new QueryRequest(type);
		workItemQuery.setFetch(new Fetch("ObjectID", "JiraID", "FormattedID", "_ref"));
		workItemQuery.setQueryFilter(new QueryFilter("JiraID", "=", storyJiraID));
		workItemQuery.setWorkspace("workspace/" + workspaceID);
		workItemQuery.setProject("project/" + projectID);
		workItemQuery.setScopedDown(true);
		logger.info("Query: " + workItemQuery.toUrl());
		QueryResponse storyResponse;
		JsonObject story = null;
		storyResponse = restApi.query(workItemQuery);
		logger.info("results: " + storyResponse.getResults());
		if (storyResponse.getResults().size() > 0) {
		JsonElement result = storyResponse.getResults().get(0);
		story = result.getAsJsonObject();
		} else {
			logger.warning("Could not find, JiraID: " + storyJiraID);
		}
		return story;
	}

	private String getRefFromContainerName(String containerType, String containerName)
			throws IOException {
		QueryRequest query = new QueryRequest(containerType);
		query.setFetch(new Fetch("ObjectID"));
		query.setQueryFilter(new QueryFilter("Name", "=", containerName));
		logger.info("query: " + query.toUrl());
		QueryResponse response;
		response = restApi.query(query);
		JsonObject container = response.getResults().get(0).getAsJsonObject();

		if (container == null) {
			return null;
		} else {
			return container.get("ObjectID").getAsString();
		}

	}
}
