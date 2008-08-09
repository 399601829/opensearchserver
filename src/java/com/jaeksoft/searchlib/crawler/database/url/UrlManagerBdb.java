/**   
 * License Agreement for Jaeksoft SearchLib Community
 *
 * Copyright (C) 2008 Emmanuel Keller / Jaeksoft
 * 
 * http://www.jaeksoft.com
 * 
 * This file is part of Jaeksoft SearchLib Community.
 *
 * Jaeksoft SearchLib Community is free software: you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 * Jaeksoft SearchLib Community is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with Jaeksoft SearchLib Community. 
 *  If not, see <http://www.gnu.org/licenses/>.
 **/

package com.jaeksoft.searchlib.crawler.database.url;

import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

import com.jaeksoft.searchlib.crawler.database.CrawlDatabaseBdb;
import com.jaeksoft.searchlib.crawler.database.CrawlDatabaseException;
import com.jaeksoft.searchlib.crawler.database.pattern.PatternUrlManager;
import com.jaeksoft.searchlib.crawler.spider.Crawl;
import com.jaeksoft.searchlib.crawler.spider.Link;
import com.jaeksoft.searchlib.crawler.spider.LinkList;
import com.jaeksoft.searchlib.crawler.spider.Parser;
import com.jaeksoft.searchlib.util.BdbJoin;
import com.jaeksoft.searchlib.util.BdbUtil;
import com.sleepycat.bind.tuple.TupleBinding;
import com.sleepycat.bind.tuple.TupleInput;
import com.sleepycat.bind.tuple.TupleOutput;
import com.sleepycat.je.Cursor;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.Environment;
import com.sleepycat.je.OperationStatus;
import com.sleepycat.je.SecondaryConfig;
import com.sleepycat.je.SecondaryDatabase;
import com.sleepycat.je.SecondaryKeyCreator;
import com.sleepycat.je.Transaction;

public class UrlManagerBdb extends UrlManager implements SecondaryKeyCreator {

	public class UrlItemTupleBinding extends BdbUtil<UrlItem> {

		@Override
		public UrlItem entryToObject(TupleInput input) {
			UrlItem urlItem = new UrlItem();
			urlItem.setUrl(input.readString());
			urlItem.setHost(input.readString());
			urlItem.setFetchStatusInt(input.readInt());
			urlItem.setParserStatusInt(input.readInt());
			urlItem.setIndexStatusInt(input.readInt());
			urlItem.setWhen(new Timestamp(input.readLong()));
			return urlItem;
		}

		@Override
		public void objectToEntry(UrlItem urlItem, TupleOutput output) {
			output.writeString(urlItem.getUrl());
			output.writeString(urlItem.getHost());
			output.writeInt(urlItem.getFetchStatus().value);
			output.writeInt(urlItem.getParserStatus().value);
			output.writeInt(urlItem.getIndexStatus().value);
			if (urlItem.getWhen() == null)
				output.writeLong(0);
			else
				output.writeLong(urlItem.getWhen().getTime());
			output.writeInt(urlItem.getRetry());
		}

		@Override
		public DatabaseEntry getKey(UrlItem item)
				throws UnsupportedEncodingException {
			DatabaseEntry key = new DatabaseEntry();
			setKey(item.getUrl(), key);
			return key;
		}

	}

	private CrawlDatabaseBdb crawlDatabase;
	private Database urlDb = null;
	private UrlItemTupleBinding tupleBinding = null;
	private SecondaryDatabase urlHostDb = null;
	private SecondaryDatabase urlFetchStatusDb = null;
	private SecondaryDatabase urlParserStatusDb = null;
	private SecondaryDatabase urlIndexStatusDb = null;
	private SecondaryDatabase urlWhenDb = null;
	private SecondaryDatabase urlRetryDb = null;

	public UrlManagerBdb(CrawlDatabaseBdb crawlDatabase)
			throws CrawlDatabaseException {
		this.crawlDatabase = crawlDatabase;
		DatabaseConfig dbConfig = new DatabaseConfig();
		dbConfig.setAllowCreate(true);
		dbConfig.setTransactional(true);
		try {
			Environment dbEnv = crawlDatabase.getEnv();
			urlDb = dbEnv.openDatabase(null, "url", dbConfig);
			tupleBinding = new UrlItemTupleBinding();
			urlHostDb = createSecondary(dbEnv, "url_host");
			urlFetchStatusDb = createSecondary(dbEnv, "url_fetch_status");
			urlParserStatusDb = createSecondary(dbEnv, "url_parser_status");
			urlIndexStatusDb = createSecondary(dbEnv, "url_index_status");
			urlWhenDb = createSecondary(dbEnv, "url_when");
			urlRetryDb = createSecondary(dbEnv, "url_retry");
		} catch (DatabaseException e) {
			throw new CrawlDatabaseException(e);
		}
	}

	private SecondaryDatabase createSecondary(Environment dbEnv, String name)
			throws DatabaseException {
		SecondaryConfig secConfig = new SecondaryConfig();
		secConfig.setAllowCreate(true);
		secConfig.setSortedDuplicates(true);
		secConfig.setAllowPopulate(true);
		secConfig.setKeyCreator(this);
		secConfig.setTransactional(true);
		return dbEnv.openSecondaryDatabase(null, name, urlDb, secConfig);
	}

	public void close() throws DatabaseException {
		if (urlDb != null) {
			urlDb.close();
			urlDb = null;
		}
	}

	@Override
	public void delete(String url) throws CrawlDatabaseException {
		DatabaseEntry key = new DatabaseEntry();
		TupleBinding.getPrimitiveBinding(String.class).objectToEntry(url, key);
		try {
			urlDb.delete(null, key);
		} catch (DatabaseException e) {
			throw new CrawlDatabaseException(e);
		}
	}

	private void getUnfetchedHostToFetch(Transaction txn, int limit,
			HashSet<String> hostSet) throws CrawlDatabaseException {
		BdbJoin join = null;
		try {
			txn = crawlDatabase.getEnv().beginTransaction(null, null);
			join = new BdbJoin();

			DatabaseEntry key = new DatabaseEntry();
			TupleBinding.getPrimitiveBinding(Integer.class).objectToEntry(
					FetchStatus.ALL.value, key);
			if (!join.add(txn, key, urlFetchStatusDb))
				return;

			// TODO

		} catch (DatabaseException e) {
			throw new CrawlDatabaseException(e);
		} finally {
			try {
				if (join != null)
					join.close();
			} catch (DatabaseException e) {
				throw new CrawlDatabaseException(e);
			}
		}
	}

	private void getExpiredHostToFetch(Transaction txn, int fetchInterval,
			int limit, HashSet<String> hostSet) throws CrawlDatabaseException {
		BdbJoin join = null;
		try {
			txn = crawlDatabase.getEnv().beginTransaction(null, null);
			join = new BdbJoin();

			// TODO Auto-generated method stub

		} catch (DatabaseException e) {
			throw new CrawlDatabaseException(e);
		} finally {
			try {
				if (join != null)
					join.close();
			} catch (DatabaseException e) {
				throw new CrawlDatabaseException(e);
			}
		}

	}

	@Override
	public List<HostItem> getHostToFetch(int fetchInterval, int limit)
			throws CrawlDatabaseException {
		Transaction txn = null;
		BdbJoin join = null;
		try {
			HashSet<String> hostSet = new HashSet<String>();
			txn = crawlDatabase.getEnv().beginTransaction(null, null);
			join = new BdbJoin();

			getUnfetchedHostToFetch(txn, limit, hostSet);
			getExpiredHostToFetch(txn, fetchInterval, limit, hostSet);

			List<HostItem> hostList = new ArrayList<HostItem>();
			Iterator<String> it = hostSet.iterator();
			while (it.hasNext())
				hostList.add(new HostItem(it.next()));
			return hostList;
		} catch (DatabaseException e) {
			throw new CrawlDatabaseException(e);
		} finally {
			try {
				if (join != null)
					join.close();
			} catch (DatabaseException e) {
				e.printStackTrace();
			}
			try {
				if (txn != null)
					txn.abort();
			} catch (DatabaseException e) {
				e.printStackTrace();
			}
		}
	}

	public class CompareUrlToFetch implements Comparator<UrlItem> {

		private long timestamp;

		public CompareUrlToFetch(long fetchInterval) {
			timestamp = getNewTimestamp(fetchInterval).getTime();
		}

		@Override
		public int compare(UrlItem item, UrlItem fake) {
			if (item.getFetchStatus() == FetchStatus.UN_FETCHED)
				return 0;
			if (item.getWhen().getTime() < timestamp)
				return 0;
			return -1;
		}

	}

	@Override
	public List<UrlItem> getUrlToFetch(HostItem host, int fetchInterval,
			int limit) throws CrawlDatabaseException {
		Transaction txn = null;
		BdbJoin join = null;
		try {
			txn = crawlDatabase.getEnv().beginTransaction(null, null);
			join = new BdbJoin();
			List<UrlItem> list = new ArrayList<UrlItem>();
			DatabaseEntry key = new DatabaseEntry();
			TupleBinding.getPrimitiveBinding(String.class).objectToEntry(
					host.getHost(), key);
			if (!join.add(txn, key, urlHostDb))
				return list;
			tupleBinding.getFilter(join.getJoinCursor(urlDb), list, limit,
					new CompareUrlToFetch(fetchInterval));
			return list;
		} catch (DatabaseException e) {
			throw new CrawlDatabaseException(e);
		} finally {
			try {
				if (join != null)
					join.close();
			} catch (DatabaseException e) {
				e.printStackTrace();
			}
			try {
				if (txn != null)
					txn.abort();
			} catch (DatabaseException e) {
				e.printStackTrace();
			}
		}
	}

	@Override
	public void inject(List<InjectUrlItem> list) throws CrawlDatabaseException {
		try {
			Iterator<InjectUrlItem> it = list.iterator();
			while (it.hasNext()) {
				InjectUrlItem item = it.next();
				if (item.getStatus() != InjectUrlItem.Status.UNDEFINED)
					continue;
				UrlItem urlItem = new UrlItem();
				urlItem.setUrl(item.getUrl());
				try {
					OperationStatus result = urlDb.putNoOverwrite(null,
							tupleBinding.getKey(urlItem), tupleBinding
									.getData(urlItem));
					if (result == OperationStatus.SUCCESS)
						item.setStatus(InjectUrlItem.Status.INJECTED);
					else if (result == OperationStatus.KEYEXIST)
						item.setStatus(InjectUrlItem.Status.ALREADY);
				} catch (IllegalArgumentException e) {
					item.setStatus(InjectUrlItem.Status.ERROR);
				} catch (UnsupportedEncodingException e) {
					item.setStatus(InjectUrlItem.Status.ERROR);
				}
			}
		} catch (DatabaseException e) {
			throw new CrawlDatabaseException(e);
		}
	}

	private void discoverLinks(LinkList links) throws IllegalArgumentException,
			DatabaseException, UnsupportedEncodingException,
			CrawlDatabaseException {
		PatternUrlManager patternManager = crawlDatabase.getPatternUrlManager();
		for (Link link : links.values())
			if (link.getFollow())
				if (patternManager.findPatternUrl(link.getUrl()) != null) {
					UrlItem urlItem = new UrlItem();
					urlItem.setUrl(link.getUrl().toExternalForm());
					urlDb.putNoOverwrite(null, tupleBinding.getKey(urlItem),
							tupleBinding.getData(urlItem));
				}
	}

	@Override
	public void update(Crawl crawl) throws CrawlDatabaseException,
			MalformedURLException {
		UrlItem urlItem = crawl.getUrlItem();
		try {
			urlDb.put(null, tupleBinding.getKey(urlItem), tupleBinding
					.getData(urlItem));
			Parser parser = crawl.getParser();
			if (parser != null && urlItem.isStatusFull()) {
				discoverLinks(parser.getInlinks());
				discoverLinks(parser.getOutlinks());
			}
		} catch (IllegalArgumentException e) {
			throw new CrawlDatabaseException(e);
		} catch (DatabaseException e) {
			throw new CrawlDatabaseException(e);
		} catch (UnsupportedEncodingException e) {
			throw new CrawlDatabaseException(e);
		}

	}

	public boolean createSecondaryKey(SecondaryDatabase secDb,
			DatabaseEntry key, DatabaseEntry data, DatabaseEntry result)
			throws DatabaseException {
		UrlItem urlItem = tupleBinding.entryToObject(data);
		if (secDb == urlHostDb) {
			TupleBinding.getPrimitiveBinding(String.class).objectToEntry(
					urlItem.getHost(), result);
		} else if (secDb == urlFetchStatusDb) {
			TupleBinding.getPrimitiveBinding(Integer.class).objectToEntry(
					urlItem.getFetchStatus().value, result);
		} else if (secDb == urlParserStatusDb) {
			TupleBinding.getPrimitiveBinding(Integer.class).objectToEntry(
					urlItem.getParserStatus().value, result);
		} else if (secDb == urlIndexStatusDb) {
			TupleBinding.getPrimitiveBinding(Integer.class).objectToEntry(
					(Integer) urlItem.getIndexStatus().value, result);
		} else if (secDb == urlWhenDb) {
			TupleBinding.getPrimitiveBinding(Long.class).objectToEntry(
					urlItem.getWhen().getTime(), result);
		} else if (secDb == urlRetryDb) {
			TupleBinding.getPrimitiveBinding(Integer.class).objectToEntry(
					urlItem.getRetry(), result);
		}
		return true;
	}

	private int getUrls(Transaction txn, String host, FetchStatus fetchStatus,
			ParserStatus parserStatus, IndexStatus indexStatus, Date startDate,
			Date endDate, int start, int rows, List<UrlItem> list)
			throws CrawlDatabaseException {

		BdbJoin join = null;

		try {

			join = new BdbJoin();

			if (host.length() > 0) {
				DatabaseEntry key = new DatabaseEntry();
				TupleBinding.getPrimitiveBinding(String.class).objectToEntry(
						host, key);
				if (!join.add(txn, key, urlHostDb))
					return 0;
			}
			if (fetchStatus != FetchStatus.ALL) {
				DatabaseEntry key = new DatabaseEntry();
				TupleBinding.getPrimitiveBinding(Integer.class).objectToEntry(
						fetchStatus.value, key);
				if (!join.add(txn, key, urlFetchStatusDb))
					return 0;
			}
			if (parserStatus != ParserStatus.ALL) {
				DatabaseEntry key = new DatabaseEntry();
				TupleBinding.getPrimitiveBinding(Integer.class).objectToEntry(
						parserStatus.value, key);
				if (!join.add(txn, key, urlParserStatusDb))
					return 0;
			}
			if (indexStatus != IndexStatus.ALL) {
				DatabaseEntry key = new DatabaseEntry();
				TupleBinding.getPrimitiveBinding(Integer.class).objectToEntry(
						indexStatus.value, key);
				if (!join.add(txn, key, urlIndexStatusDb))
					return 0;
			}

			return tupleBinding.getLimit(join.getJoinCursor(urlDb), start,
					rows, list);

		} catch (DatabaseException dbe) {
			throw new CrawlDatabaseException(dbe);
		} finally {
			try {
				if (join != null)
					join.close();
			} catch (DatabaseException dbe) {
				throw new CrawlDatabaseException(dbe);
			}
		}
	}

	@Override
	public List<UrlItem> getUrls(String like, String host,
			FetchStatus fetchStatus, ParserStatus parserStatus,
			IndexStatus indexStatus, Date startDate, Date endDate,
			Field orderBy, int start, int rows, UrlList urlList)
			throws CrawlDatabaseException {

		if (like == null)
			like = "";
		else
			like.trim();
		if (host == null)
			host = "";
		else
			host.trim();
		if (fetchStatus == null)
			fetchStatus = FetchStatus.ALL;
		if (parserStatus == null)
			parserStatus = ParserStatus.ALL;
		if (indexStatus == null)
			indexStatus = IndexStatus.ALL;

		Transaction txn = null;
		Cursor cursor = null;
		try {
			txn = crawlDatabase.getEnv().beginTransaction(null, null);

			List<UrlItem> list = new ArrayList<UrlItem>();

			if (like.length() == 0 && host.length() == 0
					&& fetchStatus == FetchStatus.ALL
					&& parserStatus == ParserStatus.ALL
					&& indexStatus == IndexStatus.ALL && startDate == null
					&& endDate == null) {
				cursor = urlDb.openCursor(txn, null);
				urlList.setNewList(list, tupleBinding.getLimit(cursor, start,
						rows, list));
			} else {
				urlList.setNewList(list, getUrls(txn, host, fetchStatus,
						parserStatus, indexStatus, startDate, endDate, start,
						rows, list));
			}

		} catch (DatabaseException e) {
			throw new CrawlDatabaseException(e);
		} finally {
			try {
				if (cursor != null)
					cursor.close();
			} catch (DatabaseException e) {
				e.printStackTrace();
			}
			try {
				if (txn != null)
					txn.abort();
			} catch (DatabaseException e) {
				e.printStackTrace();
			}
		}
		return null;
	}
}
