package com.javachina.service.impl;

import com.blade.ioc.annotation.Inject;
import com.blade.ioc.annotation.Service;
import com.blade.jdbc.ActiveRecord;
import com.blade.jdbc.core.Take;
import com.blade.jdbc.model.Paginator;
import com.blade.kit.CollectionKit;
import com.blade.kit.DateKit;
import com.blade.kit.StringKit;
import com.javachina.ImageTypes;
import com.javachina.Types;
import com.javachina.exception.TipException;
import com.javachina.kit.Utils;
import com.javachina.model.*;
import com.javachina.service.*;

import java.util.*;

@Service
public class TopicServiceImpl implements TopicService {

	@Inject
	private ActiveRecord activeRecord;

	@Inject
	private UserService userService;
	
	@Inject
	private NodeService nodeService;
	
	@Inject
	private CommentService commentService;
	
	@Inject
	private NoticeService noticeService;
	
	@Inject
	private SettingsService settingsService;
	
	@Inject
	private TopicCountService topicCountService;
	
	@Override
	public Topic getTopic(Integer tid) {
		return activeRecord.byId(Topic.class, tid);
	}

	@Override
	public Paginator<Map<String, Object>> getPageList(Take take) {
		if(null != take){
			Paginator<Topic> topicPage = activeRecord.page(take);
			return this.getTopicPageMap(topicPage);
		}
		return null;
	}
	
	private List<Map<String, Object>> getTopicListMap(List<Topic> topics){
		List<Map<String, Object>> topicMaps = new ArrayList<Map<String,Object>>();
		if(null != topics && topics.size() > 0){
			for(Topic topic : topics){
				Map<String, Object> map = this.getTopicMap(topic, false);
				if(null != map && !map.isEmpty()){
					topicMaps.add(map);
				}
			}
		}
		return topicMaps;
	}
	
	private Paginator<Map<String, Object>> getTopicPageMap(Paginator<Topic> topicPage){
		long totalCount = topicPage.getTotal();
		int page = topicPage.getPageNum();
		int pageSize = topicPage.getLimit();
		Paginator<Map<String, Object>> result = new Paginator<>(totalCount, page, pageSize);
		
		List<Topic> topics = topicPage.getList();
		List<Map<String, Object>> topicMaps = this.getTopicListMap(topics);
		result.setList(topicMaps);
		return result;
	}
	
	@Override
	public Integer save(Topic topic) throws Exception {
		if(null == topic){
			throw new TipException("帖子信息为空");
		}
		try {
			Integer time = DateKit.getCurrentUnixTime();
			topic.setCreate_time(time);
			topic.setUpdate_time(time);
			topic.setStatus(1);

			Integer tid = activeRecord.insert(topic);
			topicCountService.save(tid, time);
			this.updateWeight(tid);
			// 更新节点下的帖子数
			nodeService.updateCount(topic.getNid(), Types.topics.toString(), +1);
			// 更新总贴数
			settingsService.updateCount(Types.topic_count.toString(), +1);

			// 通知@的人
			Set<String> atUsers = Utils.getAtUsers(topic.getContent());
			if(CollectionKit.isNotEmpty(atUsers)){
				for(String user_name : atUsers){
					User user = userService.getUserByLoginName(user_name);
					if(null != user && !user.getUid().equals(topic.getUid())){
						noticeService.save(Types.topic_at.toString(), user.getUid(), tid);
					}
				}
			}
			return tid;
		} catch (Exception e) {
			throw e;
		}
	}
	
	@Override
	public void delete(Integer tid) throws Exception {
		try {
			if(null == tid){
				throw new TipException("帖子id为空");
			}
			Topic topic = new Topic();
			topic.setTid(tid);
			topic.setStatus(2);
			activeRecord.update(topic);

			// 更新节点下的帖子数
			nodeService.updateCount(topic.getNid(), Types.topics.toString(), +1);
			// 更新总贴数
			settingsService.updateCount(Types.topic_count.toString(), +1);
		} catch (Exception e){
			throw e;
		}
	}

	@Override
	public Map<String, Object> getTopicMap(Topic topic, boolean isDetail) {
		if(null == topic){
			return null;
		}
		Integer tid = topic.getTid();
		Integer uid = topic.getUid();
		Integer nid = topic.getNid();
		
		User user = userService.getUser(uid);
		Node node = nodeService.getNode(nid);
		
		if(null == user || null == node){
			return null;
		}
		
		Map<String, Object> map = new HashMap<String, Object>();
		map.put("tid", tid);
		
		TopicCount topicCount = topicCountService.getCount(tid);
		Integer views = 0, loves = 0, favorites = 0, comments = 0;
		if(null != topicCount){
			views = topicCount.getViews();
			loves = topicCount.getLoves();
			favorites = topicCount.getFavorites();
			comments = topicCount.getComments();
		}
		
		map.put("views", views);
		map.put("loves", loves);
		map.put("favorites", favorites);
		map.put("comments", comments);
		map.put("title", topic.getTitle());
		map.put("is_essence", topic.getIs_essence());
		map.put("create_time", topic.getCreate_time());
		map.put("update_time", topic.getUpdate_time());
		map.put("user_name", user.getLogin_name());
		
		String avatar = Utils.getAvatar(user.getAvatar(), ImageTypes.small);
		
		map.put("avatar", avatar);
		map.put("node_name", node.getTitle());
		map.put("node_slug", node.getSlug());
		
		if(comments > 0){
			Comment comment = commentService.getTopicLastComment(tid);
			if(null != comment){
				User reply_user = userService.getUser(comment.getUid());
				map.put("reply_name", reply_user.getLogin_name());
			}
		}
		
		if(isDetail){
			String content = Utils.markdown2html(topic.getContent());
			map.put("content", content);
		}
		return map;
	}

	

	/**
	 * 评论帖子
	 * @param uid		评论人uid
	 * @param to_uid	发帖人uid
	 * @param tid		帖子id
	 * @param content	评论内容
	 * @return
	 */
	@Override
	public boolean comment(Integer uid, Integer to_uid, Integer tid, String content, String ua) {
		try {
			Integer cid = commentService.save(uid, to_uid, tid, content, ua);
			if(null != cid){
				
				topicCountService.update(Types.comments.toString(), tid, 1);
				this.updateWeight(tid);
				
				// 通知
				if(!uid.equals(to_uid)){
					noticeService.save(Types.comment.toString(), to_uid, tid);
					
					// 通知@的用户
					Set<String> atUsers = Utils.getAtUsers(content);
					if(CollectionKit.isNotEmpty(atUsers)){
						for(String user_name : atUsers){
							User user = userService.getUserByLoginName(user_name);
							if(null != user && !user.getUid().equals(uid)){
								noticeService.save(Types.comment_at.toString(), user.getUid(), cid);
							}
						}
					}
					
					// 更新总评论数
					settingsService.updateCount(Types.comment_count.toString(), +1);
				}
				return true;
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return false;
	}

	@Override
	public Integer getTopics(Integer uid) {
		if(null != uid){
			Topic topic = new Topic();
			topic.setUid(uid);
			topic.setStatus(1);
			return activeRecord.count(topic);
		}
		return 0;
	}

	@Override
	public Integer update(Integer tid, Integer nid, String title, String content) {
		if(null != tid && null != nid && StringKit.isNotBlank(title) && StringKit.isNotBlank(content)){
			try {
				Topic topic = new Topic();
				topic.setTid(tid);
				topic.setNid(nid);
				topic.setTitle(title);
				topic.setContent(content);
				topic.setUpdate_time(DateKit.getCurrentUnixTime());
				activeRecord.update(topic);
				return tid;
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		return null;
	}

	@Override
	public Integer getLastCreateTime(Integer uid) {
		if(null == uid){
			return null;
		}
		return activeRecord.one(Integer.class, "select create_time from t_topic where uid = ? order by create_time desc limit 1", uid);
	}
	
	@Override
	public Integer getLastUpdateTime(Integer uid) {
		if(null == uid){
			return null;
		}
		return activeRecord.one(Integer.class, "select update_time from t_topic where uid = ? order by create_time desc limit 1", uid);
	}
	
	@Override
	public void refreshWeight() throws Exception {
		try {
			List<Integer> topics = activeRecord.list("select tid from t_topic where status = 1", Integer.class);
			if(null != topics) {
				for(Integer tid : topics){
					this.updateWeight(tid);
				}
			}
		} catch (Exception e){
			throw e;
		}
	}

	public void updateWeight(Integer tid, Integer loves, Integer favorites, Integer comment, Integer sinks, Integer create_time) {
		try {
			double weight = Utils.getWeight(loves, favorites, comment, sinks, create_time);
			Topic topic = new Topic();
			topic.setTid(tid);
			topic.setWeight(weight);
			activeRecord.update(topic);
		} catch (Exception e) {
			throw e;
		}
	}

	@Override
	public Paginator<Map<String, Object>> getHotTopic(Integer nid, Integer page, Integer count) {
		if(null == page || page < 1){
			page = 1;
		}
		Take tp = new Take(Topic.class);
		if(null != nid){
			tp.eq("nid", nid);
		}
		tp.eq("status", 1).orderby("weight desc").page(page, count);
		return this.getPageList(tp);
	}

	@Override
	public Paginator<Map<String, Object>> getRecentTopic(Integer nid, Integer page, Integer count) {
		if(null == page || page < 1){
			page = 1;
		}
		Take tp = new Take(Topic.class);
		if(null != nid){
			tp.eq("nid", nid);
		}
		tp.eq("status", 1).orderby("create_time desc").page(page, count);
		return this.getPageList(tp);
	}

	@Override
	public void essence(Integer tid, Integer count) {
		try {
			Topic topic = new Topic();
			topic.setTid(tid);
			topic.setIs_essence(count);
			activeRecord.update(topic);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public void updateWeight(Integer tid) throws Exception {
		try {
			if(null == tid){
				throw new TipException("帖子id为空");
			}

			TopicCount topicCount = topicCountService.getCount(tid);
			Integer loves = topicCount.getLoves();
			Integer favorites = topicCount.getFavorites();
			Integer comment = topicCount.getComments();
			Integer sinks = topicCount.getSinks();
			Integer create_time = topicCount.getCreate_time();
			this.updateWeight(tid, loves, favorites, comment, sinks, create_time);
		} catch (Exception e){
			throw e;
		}
	}

}
