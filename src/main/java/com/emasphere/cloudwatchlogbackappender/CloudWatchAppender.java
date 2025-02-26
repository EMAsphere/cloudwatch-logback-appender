package com.emasphere.cloudwatchlogbackappender;

import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.Layout;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import ch.qos.logback.core.spi.AppenderAttachable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.WebIdentityTokenFileCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.internal.util.EC2MetadataUtils;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.*;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeTagsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeTagsResponse;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.TagDescription;

/**
 * CloudWatch log appender for logback.
 * 
 * @author graywatson
 */
public class CloudWatchAppender extends UnsynchronizedAppenderBase<ILoggingEvent>
		implements AppenderAttachable<ILoggingEvent> {

	/** size of batch to write to cloudwatch api */
	private static final int DEFAULT_MAX_BATCH_SIZE = 128;

	/** time in millis to wait until we have a bunch of events to write */
	private static final long DEFAULT_MAX_BATCH_TIME_MILLIS = 5000;

	/** internal event queue size before we drop log requests on the floor */
	private static final int DEFAULT_INTERNAL_QUEUE_SIZE = 8192;

	/** create log destination group and stream when we startup */
	private static final boolean DEFAULT_CREATE_LOG_DESTS = true;

	/** max time to wait in millis before dropping a log event on the floor */
	private static final long DEFAULT_MAX_QUEUE_WAIT_TIME_MILLIS = 100;

	/** time to wait to initialize which helps when application is starting up */
	private static final long DEFAULT_INITIAL_WAIT_TIME_MILLIS = 0;

	/** how many times to retry a cloudwatch request */
	private static final int PUT_REQUEST_RETRY_COUNT = 2;

	/** property looked for to find the aws access-key-id */
	private static final String AWS_ACCESS_KEY_ID_PROPERTY = "cloudwatchappender.aws.accessKeyId";

	/** property looked for to find the aws secret-key */
	private static final String AWS_SECRET_KEY_PROPERTY = "cloudwatchappender.aws.secretKey";

	private String accessKeyId;
	private String logGroupName;
	private String logStreamName;
	private String region;
	private String secretKey;
	private Layout<ILoggingEvent> layout;
	private Appender<ILoggingEvent> emergencyAppender;
	private int maxBatchSize = DEFAULT_MAX_BATCH_SIZE;
	private long maxBatchTimeMillis = DEFAULT_MAX_BATCH_TIME_MILLIS;
	private long maxQueueWaitTimeMillis = DEFAULT_MAX_QUEUE_WAIT_TIME_MILLIS;
	private int internalQueueSize = DEFAULT_INTERNAL_QUEUE_SIZE;
	private boolean createLogDests = DEFAULT_CREATE_LOG_DESTS;
	private long initialWaitTimeMillis = DEFAULT_INITIAL_WAIT_TIME_MILLIS;

	private CloudWatchLogsClient awsLogsClient;
	private long eventsWrittenCount;

	private BlockingQueue<ILoggingEvent> loggingEventQueue;
	private Thread cloudWatchWriterThread;
	private final ThreadLocal<Boolean> stopMessagesThreadLocal = new ThreadLocal<>();
	private volatile boolean warningMessagePrinted;
	private final InputLogEventComparator inputLogEventComparator = new InputLogEventComparator();

	public CloudWatchAppender() {
		// for spring
	}

	/**
	 * After all of the setters, call initial to setup the appender.
	 */
	@Override
	public void start() {
		if (started) {
			return;
		}
		/*
		 * NOTE: as we startup here, we can't make any log calls so we can't make any RPC calls or anything without
		 * going recursive.
		 */
		if (MiscUtils.isBlank(region)) {
			throw new IllegalStateException("Region not set or invalid for appender: " + region);
		}
		if (MiscUtils.isBlank(logGroupName)) {
			throw new IllegalStateException("Log group name not set or invalid for appender: " + logGroupName);
		}
		if (MiscUtils.isBlank(logStreamName)) {
			throw new IllegalStateException("Log stream name not set or invalid for appender: " + logStreamName);
		}
		if (layout == null) {
			throw new IllegalStateException("Layout was not set for appender");
		}

		loggingEventQueue = new ArrayBlockingQueue<ILoggingEvent>(internalQueueSize);

		// create our writer thread in the background
		cloudWatchWriterThread = new Thread(new CloudWatchWriter(), getClass().getSimpleName());
		cloudWatchWriterThread.setDaemon(true);
		cloudWatchWriterThread.start();

		if (emergencyAppender != null && !emergencyAppender.isStarted()) {
			emergencyAppender.start();
		}
		super.start();
	}

	@Override
	public void stop() {
		if (!started) {
			return;
		}

		cloudWatchWriterThread.interrupt();
		try {
			cloudWatchWriterThread.join(1000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		if (awsLogsClient != null) {
			awsLogsClient.close();
			awsLogsClient = null;
		}

		super.stop();
	}

	@Override
	protected void append(ILoggingEvent loggingEvent) {

		// check wiring
		if (loggingEventQueue == null) {
			if (!warningMessagePrinted) {
				System.err.println(getClass().getSimpleName() + " not wired correctly, ignoring all log messages");
				warningMessagePrinted = true;
			}
			return;
		}

		// skip it if we just went recursive
		Boolean stopped = stopMessagesThreadLocal.get();
		if (stopped == null || !stopped) {
			try {
				if (loggingEvent instanceof LoggingEvent && loggingEvent.getThreadName() == null) {
					// we need to do this so that the right thread gets set in the event
					((LoggingEvent) loggingEvent).setThreadName(Thread.currentThread().getName());
				}
				if (!loggingEventQueue.offer(loggingEvent, maxQueueWaitTimeMillis, TimeUnit.MILLISECONDS)) {
					if (emergencyAppender != null) {
						emergencyAppender.doAppend(loggingEvent);
					}
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	// not-required, default is to use the DefaultAWSCredentialsProviderChain
	public void setAccessKeyId(String accessKeyId) {
		this.accessKeyId = accessKeyId;
	}

	// not-required, default is to use the DefaultAWSCredentialsProviderChain
	public void setSecretKey(String secretKey) {
		this.secretKey = secretKey;
	}

	// required
	public void setRegion(String region) {
		this.region = region;
	}

	// required
	public void setLogGroup(String logGroupName) {
		this.logGroupName = logGroupName;
	}

	// required
	public void setLogStream(String logStreamName) {
		this.logStreamName = logStreamName;
	}

	// required
	public void setLayout(Layout<ILoggingEvent> layout) {
		this.layout = layout;
	}

	// not-required, default is DEFAULT_MAX_BATCH_SIZE
	public void setMaxBatchSize(int maxBatchSize) {
		this.maxBatchSize = maxBatchSize;
	}

	// not-required, default is DEFAULT_MAX_BATCH_TIME_MILLIS
	public void setMaxBatchTimeMillis(long maxBatchTimeMillis) {
		this.maxBatchTimeMillis = maxBatchTimeMillis;
	}

	// not-required, default is DEFAULT_MAX_QUEUE_WAIT_TIME_MILLIS
	public void setMaxQueueWaitTimeMillis(long maxQueueWaitTimeMillis) {
		this.maxQueueWaitTimeMillis = maxQueueWaitTimeMillis;
	}

	// not-required, default is DEFAULT_INTERNAL_QUEUE_SIZE
	public void setInternalQueueSize(int internalQueueSize) {
		this.internalQueueSize = internalQueueSize;
	}

	// not-required, default is DEFAULT_CREATE_LOG_DESTS
	public void setCreateLogDests(boolean createLogDests) {
		this.createLogDests = createLogDests;
	}

	// not-required, default is 0
	public void setInitialWaitTimeMillis(long initialWaitTimeMillis) {
		this.initialWaitTimeMillis = initialWaitTimeMillis;
	}

	// not required, for testing purposes
	void setCloudWatchLogsClient(CloudWatchLogsClient awsLogsClient) {
		this.awsLogsClient = awsLogsClient;
	}

	// for testing purposes
	long getEventsWrittenCount() {
		return eventsWrittenCount;
	}

	// for testing purposes
	boolean isWarningMessagePrinted() {
		return warningMessagePrinted;
	}

	@Override
	public void addAppender(Appender<ILoggingEvent> appender) {
		if (emergencyAppender == null) {
			emergencyAppender = appender;
		} else {
			addWarn("One and only one appender may be attached to " + getClass().getSimpleName());
			addWarn("Ignoring additional appender named [" + appender.getName() + "]");
		}
	}

	@Override
	public Iterator<Appender<ILoggingEvent>> iteratorForAppenders() {
		throw new UnsupportedOperationException("Don't know how to create iterator");
	}

	@Override
	public Appender<ILoggingEvent> getAppender(String name) {
		if (emergencyAppender != null && name != null && name.equals(emergencyAppender.getName())) {
			return emergencyAppender;
		} else {
			return null;
		}
	}

	@Override
	public boolean isAttached(Appender<ILoggingEvent> appender) {
		return (emergencyAppender == appender);
	}

	@Override
	public void detachAndStopAllAppenders() {
		if (emergencyAppender != null) {
			emergencyAppender.stop();
			emergencyAppender = null;
		}
	}

	@Override
	public boolean detachAppender(Appender<ILoggingEvent> appender) {
		if (emergencyAppender == appender) {
			emergencyAppender = null;
			return true;
		} else {
			return false;
		}
	}

	@Override
	public boolean detachAppender(String name) {
		if (emergencyAppender != null && emergencyAppender.getName().equals(name)) {
			emergencyAppender = null;
			return true;
		} else {
			return false;
		}
	}

	/**
	 * Background thread that writes the log events to cloudwatch.
	 */
	private class CloudWatchWriter implements Runnable {

		private String sequenceToken;
		private String instanceId = "unknown";
		private String instanceName = "unknown";
		private String logStreamName;
		private boolean initialized;

		@Override
		public void run() {

			try {
				Thread.sleep(initialWaitTimeMillis);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}

			List<ILoggingEvent> events = new ArrayList<>(maxBatchSize);
			Thread thread = Thread.currentThread();
			while (!thread.isInterrupted()) {
				long batchTimeout = System.currentTimeMillis() + maxBatchTimeMillis;
				while (!thread.isInterrupted()) {
					long timeoutMillis = batchTimeout - System.currentTimeMillis();
					if (timeoutMillis < 0) {
						break;
					}
					ILoggingEvent loggingEvent;
					try {
						loggingEvent = loggingEventQueue.poll(timeoutMillis, TimeUnit.MILLISECONDS);
					} catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
						break;
					}
					if (loggingEvent == null) {
						// wait timed out
						break;
					}
					events.add(loggingEvent);
					if (events.size() >= maxBatchSize) {
						// batch size exceeded
						break;
					}
				}
				if (!events.isEmpty()) {
					writeEvents(events);
					events.clear();
				}
			}

			// now clear the queue and write all the rest
			events.clear();
			while (true) {
				ILoggingEvent event = loggingEventQueue.poll();
				if (event == null) {
					// nothing else waiting
					break;
				}
				events.add(event);
				if (events.size() >= maxBatchSize) {
					writeEvents(events);
					events.clear();
				}
			}
			if (!events.isEmpty()) {
				writeEvents(events);
				events.clear();
			}
		}

		private void writeEvents(List<ILoggingEvent> events) {
			if (!initialized) {
				initialized = true;
				Exception exception = null;
				try {
					stopMessagesThreadLocal.set(true);
					if (awsLogsClient == null) {
						createLogsClient();
					} else {
						// mostly here for testing
						logStreamName = buildLogStreamName();
					}
				} catch (Exception e) {
					exception = e;
				} finally {
					stopMessagesThreadLocal.set(false);
				}
				if (exception != null) {
					logError("Problems initializing cloudwatch writer", exception);
				}
			}

			// if we didn't get an aws logs-client then just write to the emergency appender (if any)
			if (awsLogsClient == null) {
				appendToEmergencyAppender(events);
				return;
			}

			// we need this in case our RPC calls create log output which we don't want to then log again
			stopMessagesThreadLocal.set(true);
			Exception exception = null;
			try {
				List<InputLogEvent> logEvents = new ArrayList<>(events.size());
				for (ILoggingEvent event : events) {
					String message = layout.doLayout(event);
					InputLogEvent logEvent = InputLogEvent.builder()
							.timestamp(event.getTimeStamp())
							.message(message)
							.build();
					logEvents.add(logEvent);
				}
				// events must be in sorted order according to AWS otherwise an exception is thrown
				logEvents.sort(inputLogEventComparator);

				for (int i = 0; i < PUT_REQUEST_RETRY_COUNT; i++) {
					try {
						PutLogEventsRequest.Builder requestBuilder = PutLogEventsRequest.builder()
								.logGroupName(logGroupName)
								.logStreamName(logStreamName)
								.logEvents(logEvents);
						if (sequenceToken != null) {
							requestBuilder.sequenceToken(sequenceToken);
						}

						PutLogEventsResponse result = awsLogsClient.putLogEvents(requestBuilder.build());
						sequenceToken = result.nextSequenceToken();
						exception = null;
						eventsWrittenCount += logEvents.size();
						break;
					} catch (InvalidSequenceTokenException iste) {
						exception = iste;
						sequenceToken = iste.expectedSequenceToken();
					}
				}
			} catch (DataAlreadyAcceptedException daac) {
				exception = daac;
				sequenceToken = daac.expectedSequenceToken();
			} catch (Exception e) {
				// catch everything else to make sure we don't quit the thread
				exception = e;
			} finally {
				if (exception != null) {
					// we do this because we don't want to go recursive
					events.add(makeEvent(Level.ERROR,
							"Exception thrown when creating logging " + events.size() + " events", exception));
					appendToEmergencyAppender(events);
				}
				stopMessagesThreadLocal.set(false);
			}
		}

		private void appendToEmergencyAppender(List<ILoggingEvent> events) {
			if (emergencyAppender != null) {
				try {
					for (ILoggingEvent event : events) {
						emergencyAppender.doAppend(event);
					}
				} catch (Exception e) {
					// oh well, we tried
				}
			}
		}

		private void createLogsClient() {
			AwsCredentialsProvider credentialProvider;
			if (MiscUtils.isBlank(accessKeyId)) {
				// try to use our class properties
				accessKeyId = System.getProperty(AWS_ACCESS_KEY_ID_PROPERTY);
				secretKey = System.getProperty(AWS_SECRET_KEY_PROPERTY);
			}

			// We are forcing the WebIdentityTokenFileCredentialsProvider
			logInfo("Using WebIdentityTokenFileCredentialsProvider");
			credentialProvider = WebIdentityTokenFileCredentialsProvider.create();

			awsLogsClient = CloudWatchLogsClient.builder()
					.credentialsProvider(credentialProvider)
					.region(Region.of(region))
					.build();

			lookupInstanceName(credentialProvider);
			logStreamName = buildLogStreamName();
			verifyLogGroupExists();
			verifyLogStreamExists();
		}

		private void verifyLogGroupExists() {
			DescribeLogGroupsRequest request = DescribeLogGroupsRequest.builder()
					.logGroupNamePrefix(logGroupName)
					.build();

			DescribeLogGroupsResponse result = awsLogsClient.describeLogGroups(request);
			for (LogGroup group : result.logGroups()) {
				if (logGroupName.equals(group.logGroupName())) {
					return;
				}
			}

			if (createLogDests) {
				CreateLogGroupRequest createLogGroupRequest = CreateLogGroupRequest.builder()
						.logGroupName(logGroupName)
						.build();
				awsLogsClient.createLogGroup(createLogGroupRequest);
			} else {
				logWarn("Log-group '" + logGroupName + "' doesn't exist and not created", null);
			}
		}

		private void verifyLogStreamExists() {
			DescribeLogStreamsRequest request = DescribeLogStreamsRequest.builder()
					.logGroupName(logGroupName)
					.logStreamNamePrefix(logStreamName)
					.build();

			DescribeLogStreamsResponse result = awsLogsClient.describeLogStreams(request);
			for (LogStream stream : result.logStreams()) {
				if (logStreamName.equals(stream.logStreamName())) {
					sequenceToken = stream.uploadSequenceToken();
					return;
				}
			}

			if (createLogDests) {
				CreateLogStreamRequest logStreamRequest = CreateLogStreamRequest.builder()
						.logGroupName(logGroupName)
						.logStreamName(logStreamName)
						.build();
				awsLogsClient.createLogStream(logStreamRequest);
			} else {
				logWarn("Log-stream '" + logStreamName + "' doesn't exist and not created", null);
			}
		}

		private String buildLogStreamName() {
			String name = CloudWatchAppender.this.logStreamName;
			if (name.indexOf('%') < 0) {
				return name;
			}
			StringBuilder sb = new StringBuilder();
			// NOTE: larger strings should be earlier in the array
			String[][] patternValues = new String[][] { { "{instanceName}", instanceName }, //
					{ "instanceName", instanceName }, //
					{ "{instanceId}", instanceId }, //
					{ "instanceId", instanceId }, //
					{ "{instance}", instanceName }, //
					{ "instance", instanceName }, //
					{ "{iid}", instanceId }, //
					{ "iid", instanceId }, //
					{ "{in}", instanceName }, //
					{ "in", instanceName }, //
			};
			// go through the name looking for %pattern that we can expand them
			OUTER: for (int i = 0; i < name.length();) {
				char ch = name.charAt(i);
				i++;
				if (ch != '%') {
					sb.append(ch);
					continue;
				}
				// run through pattern-values looking to see if the pattern is at this location, then insert value
				for (String[] patternValue : patternValues) {
					String pattern = patternValue[0];
					if (isSubstringAtPosition(name, i, pattern)) {
						sb.append(patternValue[1]);
						i += pattern.length();
						continue OUTER;
					}
				}
				sb.append(ch);
			}
			return sb.toString();
		}

		private boolean isSubstringAtPosition(CharSequence cs, int pos, CharSequence substring) {
			if (cs == null || cs.length() == 0) {
				return false;
			}
			int max = pos + substring.length();
			if (cs.length() < max) {
				return false;
			} else {
				return cs.subSequence(pos, max).equals(substring);
			}
		}

		private void lookupInstanceName(AwsCredentialsProvider credentialProvider) {
			instanceId = EC2MetadataUtils.getInstanceId();
			if (instanceId == null) {
				return;
			}
			Ec2InstanceIdConverter.setInstanceId(instanceId);
			try (Ec2Client ec2Client = Ec2Client.builder()
					.credentialsProvider(credentialProvider)
					.region(Region.of(region))
					.build()) {

				DescribeTagsRequest request = DescribeTagsRequest.builder()
						.filters(Arrays.asList(
								Filter.builder().name("resource-type").values("instance").build(),
								Filter.builder().name("resource-id").values(instanceId).build()))
						.build();

				DescribeTagsResponse result = ec2Client.describeTags(request);
				List<TagDescription> tags = result.tags();
				for (TagDescription tag : tags) {
					if ("Name".equals(tag.key())) {
						instanceName = tag.value();
						Ec2InstanceNameConverter.setInstanceName(instanceName);
						return;
					}
				}
				logInfo("Could not find EC2 instance name in tags: " + tags);
			} catch (AwsServiceException ase) {
				logWarn("Looking up EC2 instance-name threw", ase);
			}
			// if we can't lookup the instance name then set it as the instance-id
			Ec2InstanceNameConverter.setInstanceName(instanceId);
		}

		private void logInfo(String message) {
			appendEvent(Level.INFO, message, null);
		}

		private void logWarn(String message, Throwable th) {
			appendEvent(Level.WARN, message, th);
		}

		private void logError(String message, Throwable th) {
			appendEvent(Level.ERROR, message, th);
		}

		private void appendEvent(Level level, String message, Throwable th) {
			append(makeEvent(level, message, th));
		}

		private LoggingEvent makeEvent(Level level, String message, Throwable th) {
			LoggingEvent event = new LoggingEvent();
			event.setLoggerName(CloudWatchAppender.class.getName());
			event.setLevel(level);
			event.setMessage(message);
			event.setTimeStamp(System.currentTimeMillis());
			if (th != null) {
				event.setThrowableProxy(new ThrowableProxy(th));
			}
			return event;
		}
	}

	/**
	 * Compares a log event by it's timestamp value.
	 */
	private static class InputLogEventComparator implements Comparator<InputLogEvent> {
		@Override
		public int compare(InputLogEvent o1, InputLogEvent o2) {
			if (o1.timestamp() == null) {
				if (o2.timestamp() == null) {
					return 0;
				} else {
					// null - long
					return -1;
				}
			} else if (o2.timestamp() == null) {
				// long - null
				return 1;
			} else {
				return o1.timestamp().compareTo(o2.timestamp());
			}
		}
	}
}
