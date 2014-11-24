/*
 * Copyright 2014 Yahoo! Inc. Licensed under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0 Unless required by applicable law or
 * agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and
 * limitations under the License. See accompanying LICENSE file.
 */

package com.yahoo.ads.pb;

import org.apache.thrift.server.TServer;
import org.apache.thrift.server.TServer.Args;
import org.apache.thrift.server.TSimpleServer;
import org.apache.thrift.server.TThreadPoolServer;
import org.apache.thrift.transport.TSSLTransportFactory;
import org.apache.thrift.transport.TServerSocket;
import org.apache.thrift.transport.TServerTransport;
import org.apache.thrift.transport.TSSLTransportFactory.TSSLTransportParameters;
import java.nio.ByteBuffer;
import kafka.javaapi.producer.Producer;
import kafka.producer.KeyedMessage;
import kafka.producer.ProducerConfig;

import com.yahoo.ads.pb.store.StorePartition;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import com.yahoo.ads.pb.store.TKStoreFactory;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Meter;
import com.codahale.metrics.Timer;
import com.codahale.metrics.JmxReporter;


import java.net.InetAddress;
import com.yahoo.ads.pb.store.TLongKyotoCabinetStore;
import com.yahoo.ads.pb.kafka.KeyValue;
import com.yahoo.ads.pb.helix.PartitionHandler;
import com.yahoo.ads.pb.helix.PartitionHandlerFactory;
import com.yahoo.ads.pb.helix.BootstrapOnlineOfflineStateModel;
import com.yahoo.ads.pb.helix.BootstrapOnlineOfflineStateModelFactory;
import com.yahoo.ads.pb.helix.BootstrapPartitionHandler;
import com.yahoo.ads.pb.helix.HelixPartitionManager;
import com.yahoo.ads.pb.helix.HelixPartitionSpectator;
import org.apache.helix.HelixManagerFactory;
import org.apache.helix.HelixManager;
import org.apache.helix.InstanceType;
import org.apache.helix.controller.GenericHelixController;


//import com.yahoo.ads.pb.platform.perf.IncrementCounter;
//import com.yahoo.ads.pb.platform.perf.InflightCounter;
import com.yahoo.ads.pb.util.ConfigurationManager;
import com.yahoo.ads.pb.util.NativeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.configuration.Configuration;
import com.google.common.base.Joiner;



// Generated code

import java.util.HashMap;
import java.util.Properties;


public class PistachiosServer {
	static {	 
		try {	 
			NativeUtils.loadLibraryFromJar("/libkyotocabinet.so");	 
			NativeUtils.loadLibraryFromJar("/libjkyotocabinet.so");
		} catch (Exception e) {	 
			e.printStackTrace(); // This is probably not the best way to handle exception :-)	 
		}	 
	}


	private static Logger logger = LoggerFactory.getLogger(PistachiosServer.class);
	final static MetricRegistry metrics = new MetricRegistry();
	final static JmxReporter reporter = JmxReporter.forRegistry(metrics).inDomain("pistachio.metrics").build();

	private final static Meter lookupRequests = metrics.meter(MetricRegistry.name(PistachiosServer.class, "lookupRequests"));
	private final static Meter lookupFailureRequests = metrics.meter(MetricRegistry.name(PistachiosServer.class, "lookupFailureRequests"));
	private final static Meter storeRequests = metrics.meter(MetricRegistry.name(PistachiosServer.class, "storeRequests"));
	private final static Meter storeFailureRequests = metrics.meter(MetricRegistry.name(PistachiosServer.class, "storeFailureRequests"));

	private final static Timer lookupTimer = metrics.timer(MetricRegistry.name(PistachiosServer.class, "lookupTimer"));
	private final static Timer storeTimer = metrics.timer(MetricRegistry.name(PistachiosServer.class, "storeTimer"));

	static final String PROFILE_BASE_DIR = "Profile.Base";
	static final String PROFILE_NUM_STORE = "Profile.NumStore";
	static final String PROFILE_RECORDS_PER_SERVER = "Profile.RecordsPerServer";
	static final String ZOOKEEPER_SERVER = "Pistachio.ZooKeeper.Server";
	static final String PROFILE_HELIX_INSTANCE_ID = "Profile.Helix.InstanceId";

	private HelixPartitionManager<BootstrapOnlineOfflineStateModel> manager; // for partition management
	private static HelixPartitionSpectator helixPartitionSpectator;

	private TLongKyotoCabinetStore profileStore;

	private static Producer<String, byte[]> kafkaProducer = null;

	public static Producer getKafkaProducerInstance() {
		try {
			if (kafkaProducer == null) {
				synchronized (logger) {
					if (kafkaProducer == null) {
						logger.debug("first time using kafka producer, creating it");
						try {
							Properties props = new Properties();
							props.put("metadata.broker.list",
									Joiner.on(",").join(ConfigurationManager.getConfiguration().getStringArray("Kafka.Broker")));
							props.put("serializer.class", "com.yahoo.ads.pb.kafka.KeyValueEncoder");
							//props.put("combiner.class", "com.yahoo.ads.pb.application.servlet.Combiner");
							//props.put("serializer.class", "StringEncoder");
							props.put("request.required.acks", "0");
							props.put("queue.buffering.max.ms", "100");
							props.put("producer.type", "sync");
							props.put("auto.create.topics.enable", "true");

							ProducerConfig kafkaConf = new ProducerConfig(props);

							kafkaProducer = new Producer<String, byte[]>(kafkaConf);
						} catch (Throwable t) {
							logger.error("Exception in creating Producer:", t);
						}
						logger.debug("created kafka producer");
					}
				}
			}
		} catch (Throwable t) {
			logger.error("error creating kafka producer instance", t);
		}

		return kafkaProducer;
	}



  public static class PistachiosHandler implements Pistachios.Iface{
	String storage;

    public ByteBuffer lookup(long id) throws org.apache.thrift.TException
	{
		lookupRequests.mark();
		final Timer.Context context = lookupTimer.time();

		try {
			int partitionId = (int)(id % 256);
			partitionId = partitionId >= 0 ? partitionId : partitionId + 256;
			//return ByteBuffer.wrap(storage.getBytes());
			KeyValue toRetrun = PistachiosServer.storePartitionMap.get(partitionId).getWriteCache().get(id);
			if (toRetrun != null)
				return ByteBuffer.wrap(toRetrun.value);

			if (null != PistachiosServer.getInstance().getProfileStore().get(id))
				return ByteBuffer.wrap(PistachiosServer.getInstance().getProfileStore().get(id));

			return ByteBuffer.wrap("".getBytes());
		} catch (Exception e) {
			logger.info("Exception lookup {}", id, e);
			lookupFailureRequests.mark();
			return ByteBuffer.wrap("".getBytes());
		} finally {
			context.stop();
		}
	}

    public boolean store(long id, ByteBuffer value) throws org.apache.thrift.TException
	{
		storeRequests.mark();
		final Timer.Context context = storeTimer.time();

		try {
			int partitionId = (int)(id % 256);
			partitionId = partitionId >= 0 ? partitionId : partitionId + 256;
			long nextSeqId = -1;
			if ((nextSeqId = PistachiosServer.storePartitionMap.get(partitionId).getNextSeqId()) == -1) {
				return false;
			}

		  int shard = (int)(id % 256);
		  shard = shard < 0 ? shard + 256: shard;
			String partitionTopic = ConfigurationManager.getConfiguration().getString("Profile.Kafka.TopicPrefix") + (shard);
			KeyValue kv = new KeyValue();
			kv.key = id;
			kv.seqId = nextSeqId;
			kv.value = value.array();
			KeyedMessage<String, KeyValue> message = new KeyedMessage<String, KeyValue>(partitionTopic, kv);
			getKafkaProducerInstance().send(message);

			logger.info("sent msg {} {} {}, partition current seqid {}", kv.key, kv.value, kv.seqId, PistachiosServer.storePartitionMap.get(partitionId).getSeqId());

			PistachiosServer.storePartitionMap.get(partitionId).getWriteCache().put(id, kv);

				logger.info("waiting for change to catch up {} {} within gap 200", PistachiosServer.storePartitionMap.get(partitionId).getSeqId() , kv.seqId);
			while(kv.seqId - PistachiosServer.storePartitionMap.get(partitionId).getSeqId() > 200) {
				logger.info("waiting for change to catch up {} {} within gap 200", PistachiosServer.storePartitionMap.get(partitionId).getSeqId() , kv.seqId);
				Thread.sleep(30);
			}
			return true;

			//PistachiosServer.getInstance().getProfileStore().store(id, value.array());
		} catch (Exception e) {
			logger.info("error storing {} {}", id, value, e);
			storeFailureRequests.mark();
			return false;
		} finally {
			context.stop();
		}
	}
  }

  public static PistachiosHandler handler;

  public static Pistachios.Processor processor;

  private static PistachiosServer instance;

  private static HelixManager helixManager;

  private static GenericHelixController controller;// = new GenericHelixController();

  public static ConcurrentHashMap<Integer, StorePartition> storePartitionMap = new ConcurrentHashMap<Integer, StorePartition>();

  public static PistachiosServer getInstance() {
	  return instance;
  }
						

  public static void main(String [] args) {
    try {
		reporter.start();

	  // embed helix controller
		Configuration conf = ConfigurationManager.getConfiguration();
		logger.info("zk conn str {}", conf.getString(ZOOKEEPER_SERVER));
		helixManager = HelixManagerFactory.getZKHelixManager("PistachiosCluster",
				InetAddress.getLocalHost().getHostName(), //conf.getString(PROFILE_HELIX_INSTANCE_ID),
				InstanceType.CONTROLLER,
				conf.getString(ZOOKEEPER_SERVER));
		helixManager.connect();
		controller = new GenericHelixController();
		helixManager.addConfigChangeListener(controller);
		helixManager.addLiveInstanceChangeListener(controller);
		helixManager.addIdealStateChangeListener(controller);
		helixManager.addExternalViewChangeListener(controller);
		helixManager.addControllerListener(controller);


	  instance = new PistachiosServer();
	  instance.init();
      handler = new PistachiosHandler();
      processor = new Pistachios.Processor(handler);

      Runnable simple = new Runnable() {
        public void run() {
          simple(processor);
        }
      };      

      new Thread(simple).start();
    } catch (Exception x) {
      x.printStackTrace();
    }
  }

  public static void simple(Pistachios.Processor processor) {
    try {
      TServerTransport serverTransport = new TServerSocket(9090);
      //TServer server = new TSimpleServer(new Args(serverTransport).processor(processor));

      // Use this for a multithreaded server
	   TThreadPoolServer.Args args = new TThreadPoolServer.Args(serverTransport);
	   args.processor(processor);
	   args.minWorkerThreads = 50;
	   args.maxWorkerThreads = 200;
	   args.stopTimeoutUnit = TimeUnit.SECONDS;
	   args.stopTimeoutVal = 60;
       TServer server = new TThreadPoolServer(args);

      System.out.println("Starting the simple server...");
      server.serve();
    } catch (Exception e) {
		logger.info("error ", e);
      e.printStackTrace();
    }
  }

  public TLongKyotoCabinetStore getProfileStore() {
	  return profileStore;
  }

  byte[] getUserProfileLocally(long userId) {
	  if (profileStore != null) {
		  return profileStore.get(userId);
	  }

	  return null;
  }
	public boolean init() {
		boolean initialized = false;

		logger.info("Initializing profile server...........");
		try {
			// open profile store
			Configuration conf = ConfigurationManager.getConfiguration();
			profileStore = new TLongKyotoCabinetStore(
			        conf.getString(PROFILE_BASE_DIR),
			        0, 8,
			        conf.getInt("Profile.RecordsPerServer"),
			        conf.getLong("Profile.MemoryPerServer"));
			/*
			*/
			//profileStore.open();
			/*
			hostname = InetAddress.getLocalHost().getHostName();
			if(conf.getBoolean("Profile.Redesign.Firstload", false)){
				String fileName = "profileMapping.json";
				String refDir = "/home/y/libexec/server/refdata/";
				String fullName = refDir + File.separator + fileName;
				FileReader fr = new FileReader(fullName);
				JSONParser parser = new JSONParser();
				ContainerFactory containerFactory = new ContainerFactory() {
					public List creatArrayContainer() {
						return new LinkedList();
					}

					public Map createObjectContainer() {
						return new LinkedHashMap();
					}

				};
				
				Map<String,List<String>> partition2Server = (Map<String,List<String>>) parser.parse(fr,containerFactory);
				Set<String> keys = partition2Server.keySet();
				Set<Integer> partitions = new TreeSet<Integer>();
				for(String key:keys){
					if(partition2Server.get(key).contains(hostname)){
						partitions.add(Integer.parseInt(key));
					}
				}
				String profilePath = "/home/y/libexec/server/profile/store_";
				String profileFilePrefix = "db_";
				String profileFileSurfix = ".kch";
				String profileWalFileSurfix = ".wal";
				int index = 0;
				for(int partition: partitions){
					File partitionDir = new File(profilePath+index);
					if(partitionDir.isDirectory()){
						logger.info("rename from {} to {}",profilePath+index,profilePath+partition);
						partitionDir.renameTo( new File(profilePath+partition));
					}
					File storeFile = new File(profilePath+partition+File.separator+profileFilePrefix+index+profileFileSurfix);
					if(storeFile.isFile()){
						logger.info("rename from {} to {}",profilePath+partition+File.separator+profileFilePrefix+index+profileFileSurfix, profilePath+partition+File.separator+profileFilePrefix+partition+profileFileSurfix);
						storeFile.renameTo(new File(profilePath+partition+File.separator+profileFilePrefix+partition+profileFileSurfix));
					}
					File storeFileWal =  new File(profilePath+partition+File.separator+profileFilePrefix+index+profileFileSurfix+profileWalFileSurfix);
					if(storeFileWal.isFile()){
						logger.info("rename from {} to {}",profilePath+partition+File.separator+profileFilePrefix+index+profileFileSurfix+profileWalFileSurfix,profilePath+partition+File.separator+profileFilePrefix+partition+profileFileSurfix+profileWalFileSurfix);
						storeFileWal.renameTo(new File(profilePath+partition+File.separator+profileFilePrefix+partition+profileFileSurfix+profileWalFileSurfix));
					}
					
					index++;
				}
				
			}
			*/
			/*
			ModuleManager
				.registerMBean(
						ProfileServer.getInstance(),
						new ObjectName(
							"com.yahoo.ads.pb.platform.profile:name=ProfileServer"));
				*/

			// initialize performance counter
			/*
			lookupCounter = new InflightCounter(counterGroupName, "Lookup");
			lookupCounter.register();
			noDataCounter = new IncrementCounter(counterGroupName, "LookupNoData");
			noDataCounter.register();
			*/

//			storeCounter = new InflightCounter(counterGroupName, "Store");
//			storeCounter.register();
//			failedStoreCounter = new IncrementCounter(counterGroupName,
//			        "FailedStore");
//			failedStoreCounter.register();

//			boolean enableStorePartition = conf.getBoolean(
//			        Constant.PROFILE_STORING_PARTITION_ENABLE, false);
//			if (enableStorePartition) {
				
		logger.info("creating helix partition sepctator {} {} {}", conf.getString(ZOOKEEPER_SERVER, "EMPTY"),
			"PistachiosCluster", conf.getString(PROFILE_HELIX_INSTANCE_ID, "EMPTY"));
				helixPartitionSpectator = new HelixPartitionSpectator(
				        conf.getString(ZOOKEEPER_SERVER), // zkAddr
				        "PistachiosCluster",
				        InetAddress.getLocalHost().getHostName() //conf.getString(PROFILE_HELIX_INSTANCE_ID) // instanceName
				);
				// Partition Manager for line spending
				manager = new HelixPartitionManager<>(
				        conf.getString(ZOOKEEPER_SERVER), // zkAddr
				        "PistachiosCluster",
				        InetAddress.getLocalHost().getHostName() //conf.getString(PROFILE_HELIX_INSTANCE_ID) // instanceName
				);
				//manager.start("BootstrapOnlineOffline", new BootstrapOnlineOfflineStateModelFactory(new StorePartitionHandlerFactory()));
				manager.start("MasterSlave", new BootstrapOnlineOfflineStateModelFactory(new StorePartitionHandlerFactory()));
//			}

			initialized = true;
		} catch (Exception e) {
			logger.error("Failed to initialize ProfileServerModule", e);
		}
		logger.info("Finished initializing profile server...........");

		return initialized;
	}

		class StorePartitionHandlerFactory implements PartitionHandlerFactory {

			public PartitionHandler createParitionHandler(int partitionId) {
				StorePartition sp = new StorePartition(partitionId);
				sp.setStoreFactory(new TKStoreFactory());

				PistachiosServer.storePartitionMap.put(partitionId, sp);
		logger.info("creating partition handler........... {}", sp);
				return sp;
				/*
				final int partitionId1 = partitionId;
				return new BootstrapPartitionHandler() {

					int partitionId;
					public void selfBootstraping() {
						logger.info("self bootstrapping {}.", partitionId1);
					}
					public void bootstrapingOthers() {
						logger.info("bootstraping others {}.", partitionId1);
					}

					public void startServing() {
						logger.info("starting serving store {}.", partitionId1);
					}
					public void stopServing() {
						logger.info("stopping serving store {}.", partitionId1);
					}
				};
				*/

			}
		}


}
