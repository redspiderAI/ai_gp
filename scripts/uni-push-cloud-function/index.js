/**
 * uni-push 2.0 云函数示例（部署到 uniCloud 并 URL 化后，供 Java 后端 POST 调用）。
 * 使用：在 manifest 开启 uni-push 2.0，在 DCloud 开发者中心配置托管 Firebase / 厂商通道。
 * 依赖：云函数扩展库 uni-cloud-push。
 */
'use strict';

const uniPush = uniCloud.getPushManager({
	appId: '__UNI__XXXXXXXX', // 替换为 manifest 中的 AppID
});

exports.main = async (event) => {
	const body = typeof event.body === 'string' ? JSON.parse(event.body) : event.body || event;
	const cids = body.cids;
	if (!cids || (Array.isArray(cids) && cids.length === 0)) {
		return { code: 400, msg: 'cids required' };
	}
	return await uniPush.sendMessage({
		push_clientid: cids,
		title: body.title,
		content: body.content,
		payload: body.payload || {},
		force_notification: body.force_notification !== false,
		request_id: body.request_id,
		settings: body.settings,
		options: body.options,
		category: body.category,
	});
};
