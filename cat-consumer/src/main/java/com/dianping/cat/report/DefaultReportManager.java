package com.dianping.cat.report;

import static com.dianping.cat.report.ReportConstants.HOUR;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.plexus.logging.LogEnabled;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.Initializable;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.InitializationException;
import org.unidal.lookup.annotation.Inject;

import com.dianping.cat.Cat;
import com.dianping.cat.configuration.NetworkInterfaceManager;
import com.dianping.cat.consumer.core.dal.Report;
import com.dianping.cat.consumer.core.dal.ReportDao;
import com.dianping.cat.consumer.core.dal.Task;
import com.dianping.cat.consumer.core.dal.TaskDao;
import com.dianping.cat.message.Message;
import com.dianping.cat.message.Transaction;
import com.dianping.cat.report.model.ModelPeriod;
import com.dianping.cat.storage.Bucket;
import com.dianping.cat.storage.BucketManager;

/**
 * Hourly report manager by domain of one report type(such as Transaction, Event, Problem, Heartbeat etc.) produced in one machine
 * for a couple of hours.
 */
public class DefaultReportManager<T> implements ReportManager<T>, Initializable, LogEnabled {
	@Inject
	private ReportDelegate<T> m_reportDelegate;

	@Inject
	private BucketManager m_bucketManager;

	@Inject
	private ReportDao m_reportDao;

	@Inject
	private TaskDao m_taskDao;

	private String m_name;

	private Map<Long, Map<String, T>> m_map = new HashMap<Long, Map<String, T>>();

	private Logger m_logger;

	@Override
	public void cleanup() {
		long currentStartTime = ModelPeriod.CURRENT.getStartTime();
		long threshold = currentStartTime - 2 * ReportConstants.HOUR;
		List<Long> startTimes = new ArrayList<Long>(m_map.keySet());

		for (long startTime : startTimes) {
			if (startTime <= threshold) {
				m_map.remove(startTime); // too old to stay in memory
			}
		}
	}

	@Override
	public void enableLogging(Logger logger) {
		m_logger = logger;
	}

	@Override
	public T getHourlyReport(long startTime, String domain, boolean createIfNotExist) {
		Map<String, T> reports = m_map.get(startTime);

		if (reports == null && createIfNotExist) {
			synchronized (m_map) {
				reports = m_map.get(startTime);

				if (reports == null) {
					reports = new HashMap<String, T>();
					m_map.put(startTime, reports);
				}
			}
		}

		T report = reports == null ? null : reports.get(domain);

		if (report == null && createIfNotExist) {
			synchronized (reports) {
				report = m_reportDelegate.makeReport(domain, startTime, HOUR);
				reports.put(domain, report);
			}
		}

		if (report == null) {
			return report = m_reportDelegate.makeReport(domain, startTime, HOUR);
		} else {
			return report;
		}
	}

	@Override
	public Map<String, T> getHourlyReports(long startTime) {
		Map<String, T> reports = m_map.get(startTime);

		if (reports == null) {
			return Collections.emptyMap();
		} else {
			return reports;
		}
	}

	@Override
	public void initialize() throws InitializationException {
		long currentStartTime = ModelPeriod.CURRENT.getStartTime();

		loadHourlyReports(currentStartTime, StoragePolicy.FILE);
		loadHourlyReports(currentStartTime - ReportConstants.HOUR, StoragePolicy.FILE);
	}

	@Override
	public Map<String, T> loadHourlyReports(long startTime, StoragePolicy policy) {
		Transaction t = Cat.newTransaction("Restore", m_name);
		Map<String, T> reports = m_map.get(startTime);
		Bucket<String> bucket = null;

		if (reports == null) {
			reports = new HashMap<String, T>();
			m_map.put(startTime, reports);
		}

		try {
			bucket = m_bucketManager.getReportBucket(startTime, m_name);

			for (String id : bucket.getIds()) {
				String xml = bucket.findById(id);
				T report = m_reportDelegate.parseXml(xml);

				reports.put(id, report);
			}

			m_reportDelegate.afterLoad(reports);
		} catch (Throwable e) {
			t.setStatus(e);
			Cat.logError(e);
			m_logger.error(String.format("Error when loading %s reports of %s!", m_name, new Date(startTime)), e);
		} finally {
			t.complete();

			if (bucket != null) {
				m_bucketManager.closeBucket(bucket);
			}
		}

		return reports;
	}

	public void setName(String name) {
		m_name = name;
	}

	@Override
	public void storeHourlyReports(long startTime, StoragePolicy policy) {
		Transaction t = Cat.newTransaction("Checkpoint", m_name);
		Map<String, T> reports = m_map.get(startTime);
		Bucket<String> bucket = null;

		try {
			t.addData("reports", reports == null ? 0 : reports.size());

			if (reports != null) {
				m_reportDelegate.beforeSave(reports);

				if (policy.forFile()) {
					bucket = m_bucketManager.getReportBucket(startTime, m_name);

					for (T report : reports.values()) {
						try {
							String domain = m_reportDelegate.getDomain(report);
							String xml = m_reportDelegate.buildXml(report);

							bucket.storeById(domain, xml);
						} catch (Exception e) {
							t.setStatus(e);
							Cat.logError(e);
						}
					}
				}

				if (policy.forDatabase()) {
					Date period = new Date(startTime);
					String ip = NetworkInterfaceManager.INSTANCE.getLocalHostAddress();

					for (T report : reports.values()) {
						try {
							String domain = m_reportDelegate.getDomain(report);
							String xml = m_reportDelegate.buildXml(report);
							Report r = m_reportDao.createLocal();

							r.setName(m_name);
							r.setDomain(domain);
							r.setPeriod(period);
							r.setIp(ip);
							r.setType(1);
							r.setContent(xml);

							m_reportDao.insert(r);

							Task task = m_taskDao.createLocal();
							task.setCreationDate(new Date());
							task.setProducer(ip);
							task.setReportDomain(domain);
							task.setReportName(m_name);
							task.setReportPeriod(period);
							task.setStatus(1); // status todo

							m_taskDao.insert(task);
						} catch (Throwable e) {
							t.setStatus(e);
							Cat.getProducer().logError(e);
						}
					}
				}
			}

			t.setStatus(Message.SUCCESS);
		} catch (Throwable e) {
			Cat.logError(e);
			t.setStatus(e);
			m_logger.error(String.format("Error when storing %s reports of %s!", m_name, new Date(startTime)), e);
		} finally {
			t.complete();

			if (bucket != null) {
				m_bucketManager.closeBucket(bucket);
			}
		}
	}

	public static enum StoragePolicy {
		FILE,

		FILE_AND_DB;

		public boolean forDatabase() {
			return this == FILE_AND_DB;
		}

		public boolean forFile() {
			return this == FILE_AND_DB || this == FILE;
		}
	}
}
