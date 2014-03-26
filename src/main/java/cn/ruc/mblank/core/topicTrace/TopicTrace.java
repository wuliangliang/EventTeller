package cn.ruc.mblank.core.topicTrace;


import cn.ruc.mblank.db.hbn.model.*;
import cn.ruc.mblank.index.solr.EventIndex;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cn.ruc.mblank.config.JsonConfigModel;
import cn.ruc.mblank.util.Const;
import cn.ruc.mblank.util.IOReader;
import cn.ruc.mblank.util.db.Hbn;

public class TopicTrace {
	
	
	private int BatchSize = 2000;
	private List<EventStatus> EStatus;
	private List<EventSim> UpdateEventSims;
	
	private long TotalDocumentCount = 0;
	private double AvgWordIDF = 1.0;
	
	
	private Map<String,Double> IDF;
	
	private int MaxTopicId = 0;
	
	
	private String LocalTDFPath;
	private String LocalDDNPath;
	
	
	
	
	public TopicTrace(){
		JsonConfigModel jcm = JsonConfigModel.getConfig();		
		LocalTDFPath = jcm.LocalTDFPath;
		LocalDDNPath = jcm.LocalDDNPath;
		IDF = new HashMap<String,Double>();
		EStatus = new ArrayList<EventStatus>();
		UpdateEventSims = new ArrayList<EventSim>();
		loadIDF();
		String hql = "select max(id) from Topic";
        Hbn db = new Hbn();
		MaxTopicId = db.getMaxFromDB(Topic.class,"id");
	}
	

	private void getInstances(){
		String hql = "from EventStatus as obj where obj.status = " + Const.TaskId.UpdateDFSuccess.ordinal();
        Hbn db = new Hbn();
		EStatus = db.getElementsFromDB(hql,-1,BatchSize);
	}
	
	/**
	 * load Idf to memory
	 */
	private void loadIDF(){
		//load total document count from ddn
		try {
			this.TotalDocumentCount = 0;
			IOReader reader = new IOReader(LocalDDNPath);
			String line = "";
			while((line = reader.readLine()) != null){
				String[] its = line.split("\t");
				if(its.length != 2){
					continue;
				}
				this.TotalDocumentCount += Integer.parseInt(its[1]);
			}
			reader.close();
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		//load DF from df file
		try {
			IOReader reader = new IOReader(LocalTDFPath);
			String line = "";
			while((line = reader.readLine()) != null){
				String[] its = line.split("\t");
				if(its.length != 2){
					continue;
				}
				double idf = Math.log(((double)(Integer.parseInt(its[1])) / (this.TotalDocumentCount + 1.0)));
				this.AvgWordIDF += idf;
				IDF.put(its[0], idf);
			}
			reader.close();
			this.AvgWordIDF /= IDF.size();
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	
	private String titleToSearchString(String title){
		StringBuffer result = new StringBuffer("");
		List<String> words = cn.ruc.mblank.util.ChineseSplit.SplitStr(title);
		for(String word : words){
			if(word.length() >= 1){
				result.append(" " + word);
			}
		}
		return result.toString();
	}
	
	
	private Event findMostSimEvent(Event scr){
		Event mostSimEvent = null;
		double maxSimScore = -2;
		EventIndex ei = new EventIndex();
		List<Integer> ids =  ei.queryIds(titleToSearchString(scr.getTitle()), 0, Const.MaxNeighborEventCount, null, "");
		for(int id : ids){
			String getEt = "from Event as obj where obj.id = " + id;
            Hbn db = new Hbn();
			Event candidate = db.getElementFromDB(getEt);
			if(candidate == null || candidate.getPubtime().compareTo(scr.getPubtime()) > 0 || id == scr.getId()){
				continue;
			}
			double simScore = cn.ruc.mblank.util.Similarity.similarityOfEvent(scr, candidate, IDF, AvgWordIDF);
			if(simScore > maxSimScore){
				mostSimEvent = candidate;
				maxSimScore = simScore;
			}
		}
		if(maxSimScore > Const.MaxTopicSimNum){
			///find sim ..
			//add to update EventSim
			EventSim es = new EventSim();
			es.setFid(scr.getId());
			es.setSid(mostSimEvent.getId());
			es.setScore(maxSimScore);
			UpdateEventSims.add(es);
		}else{
			mostSimEvent = null;
		}
		return mostSimEvent;
	}
	
	
	private Topic createNewTopic(Event et){
		Topic res = new Topic();
		res.setId(++MaxTopicId);
		res.setEndTime(et.getPubtime());
		res.setStartTime(et.getPubtime());
		res.setNumber(1);
		res.setSummary(et.getContent());
		res.setKeyWords(et.getTitle());
		return res;
	}
	
	private int runTask(){
        Hbn db = new Hbn();
		Date readDbStart = new Date();
		getInstances();
		Date readDbEnd = new Date();
		List<Topic> updateTopics = new ArrayList<Topic>();
		List<EventTopicRelation> updateETRs = new ArrayList<EventTopicRelation>();
		HashMap<Integer,TopicStatus> updateTopicStatus = new HashMap<Integer,TopicStatus>();
		for(EventStatus es : EStatus){
			String sql = "from Event as obj where obj.id = " + es.getId();
			Event et = db.getElementFromDB(sql);
			if(et == null){
				es.setStatus((short)Const.TaskId.GenerateTopicFailed.ordinal());
				continue;
			}
			Event simEvent = findMostSimEvent(et);
			EventTopicRelation uetr = new EventTopicRelation();
			uetr.setEid(et.getId());
			if(simEvent == null){
				//no sim find .. should be new Topic
				//get max id from topic
				Topic tp = createNewTopic(et);
				updateTopics.add(tp);
				uetr.setTid(et.getId());
				TopicStatus ts = new TopicStatus();
				ts.setId(tp.getId());
				ts.setStatus((short)Const.TaskId.TopicInfoToUpdate.ordinal());
				updateTopicStatus.put(ts.getId(), ts);
			}else{
				//found..update updateETRs
				//get topic id;
				String gettopic = "from EventTopicRelation as obj where obj.id = " + simEvent.getId();
				EventTopicRelation etr = db.getElementFromDB(gettopic);
				if(etr == null){
					//because the 
					continue;
				}
				uetr.setTid(etr.getTid());
				TopicStatus ts = new TopicStatus();
				ts.setId(etr.getTid());
				ts.setStatus((short)Const.TaskId.TopicInfoToUpdate.ordinal());
				updateTopicStatus.put(ts.getId(), ts);
			}
			updateETRs.add(uetr);
			es.setStatus((short)Const.TaskId.GenerateTopicSuccess.ordinal());
		}
		Date algoEnd = new Date();
		//update event-topic table
		db.updateDB(updateETRs);
		db.updateDB(updateTopicStatus.values());
		db.updateDB(updateTopics);
		db.updateDB(UpdateEventSims);
		db.updateDB(EStatus);
		Date updateDBEnd = new Date();
		System.out.println("read db time spent.. " + (readDbEnd.getTime() - readDbStart.getTime()) / 1000);
		System.out.println("algorithm time spent.. " + (algoEnd.getTime() - readDbEnd.getTime()) / 1000);
		System.out.println("update db time spent .. " + (updateDBEnd.getTime() - algoEnd.getTime()) / 1000 );		
		return EStatus.size();
	}
	
	
	public static void main(String[] args){
		while(true){
			TopicTrace ctt = new TopicTrace();
			int num = ctt.runTask();
			System.out.println("one batch ok for Topic Tracing.." + ctt.EStatus.size());
			if(num == 0 ){
				try {
					System.out.println("now end of one cluster,sleep for:"+Const.ClusterToTopicSleepTime /1000 /60 +" minutes. "+new Date().toString());
					Thread.sleep(Const.ClusterToTopicSleepTime /60);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}			
			}
		}
	}
	
}