/**
 * Swagger UI：在 POST /api/v1/speech/transcribe 展开时注入浏览器录音测试面板。
 * 使用前请在 Swagger 右上角 Authorize 填入 Bearer accessToken。
 */
(function () {
	'use strict';

	const TRANSCRIBE_PATH = '/api/v1/speech/transcribe';
	const PANEL_CLASS = 'aigp-speech-recorder-panel';
	const MAX_RECORD_MS = 120000;

	let mediaRecorder = null;
	let recordChunks = [];
	let recordStartedAt = 0;
	let recordTimerId = null;
	let lastBlob = null;

	function getBearerToken() {
		try {
			const raw = localStorage.getItem('authorized');
			if (!raw) {
				return null;
			}
			const data = JSON.parse(raw);
			const entry = data.bearerAuth || data.Bearer || data.bearer;
			if (!entry) {
				return null;
			}
			const v = entry.value || entry.token || '';
			return v ? String(v).replace(/^Bearer\s+/i, '').trim() : null;
		} catch (e) {
			return null;
		}
	}

	function findTranscribeOpblock() {
		return Array.from(document.querySelectorAll('.opblock')).find(function (block) {
			const pathEl = block.querySelector('.opblock-summary-path');
			if (!pathEl) {
				return false;
			}
			const text = pathEl.getAttribute('data-path') || pathEl.textContent || '';
			return text.indexOf(TRANSCRIBE_PATH) !== -1;
		});
	}

	function findFileInput(opblock) {
		const inputs = opblock.querySelectorAll('input[type="file"]');
		for (let i = 0; i < inputs.length; i++) {
			const name = (inputs[i].getAttribute('name') || '').toLowerCase();
			if (name === 'file' || name.indexOf('file') !== -1) {
				return inputs[i];
			}
		}
		return inputs[0] || null;
	}

	function setStatus(panel, message, isError) {
		const el = panel.querySelector('.aigp-speech-status');
		if (el) {
			el.textContent = message;
			el.style.color = isError ? '#f87171' : '#94a3b8';
		}
	}

	function setResult(panel, text) {
		const el = panel.querySelector('.aigp-speech-result');
		if (!el) {
			return;
		}
		if (text) {
			el.textContent = text;
			el.style.display = 'block';
		} else {
			el.textContent = '';
			el.style.display = 'none';
		}
	}

	function attachBlobToFileInput(fileInput, blob) {
		if (!fileInput || !blob) {
			return;
		}
		const file = new File([blob], 'recording.webm', { type: blob.type || 'audio/webm' });
		const dt = new DataTransfer();
		dt.items.add(file);
		fileInput.files = dt.files;
		fileInput.dispatchEvent(new Event('change', { bubbles: true }));
	}

	function stopRecording(panel) {
		if (mediaRecorder && mediaRecorder.state !== 'inactive') {
			mediaRecorder.stop();
		}
		if (recordTimerId) {
			clearInterval(recordTimerId);
			recordTimerId = null;
		}
		const btnStart = panel.querySelector('.aigp-btn-record');
		const btnStop = panel.querySelector('.aigp-btn-stop');
		if (btnStart) {
			btnStart.disabled = false;
			btnStart.style.opacity = '1';
		}
		if (btnStop) {
			btnStop.disabled = true;
			btnStop.style.opacity = '0.5';
		}
	}

	async function startRecording(panel, opblock) {
		if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
			setStatus(panel, '当前浏览器不支持麦克风录音', true);
			return;
		}
		try {
			const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
			recordChunks = [];
			const mimeType = MediaRecorder.isTypeSupported('audio/webm;codecs=opus')
					? 'audio/webm;codecs=opus'
					: 'audio/webm';
			mediaRecorder = new MediaRecorder(stream, { mimeType: mimeType });
			mediaRecorder.ondataavailable = function (e) {
				if (e.data && e.data.size > 0) {
					recordChunks.push(e.data);
				}
			};
			mediaRecorder.onstop = function () {
				stream.getTracks().forEach(function (t) {
					t.stop();
				});
				lastBlob = new Blob(recordChunks, { type: mimeType.split(';')[0] });
				attachBlobToFileInput(findFileInput(opblock), lastBlob);
				const sec = ((Date.now() - recordStartedAt) / 1000).toFixed(1);
				setStatus(
						panel,
						'录音结束（' + sec + ' 秒），已填入下方 file；可点「识别并上传」或 Swagger Execute');
			};
			mediaRecorder.start(250);
			recordStartedAt = Date.now();
			const btnStart = panel.querySelector('.aigp-btn-record');
			const btnStop = panel.querySelector('.aigp-btn-stop');
			if (btnStart) {
				btnStart.disabled = true;
				btnStart.style.opacity = '0.5';
			}
			if (btnStop) {
				btnStop.disabled = false;
				btnStop.style.opacity = '1';
			}
			setStatus(panel, '正在录音… 最长 ' + MAX_RECORD_MS / 1000 + ' 秒');
			recordTimerId = setInterval(function () {
				if (Date.now() - recordStartedAt >= MAX_RECORD_MS) {
					stopRecording(panel);
				}
			}, 500);
			setTimeout(function () {
				if (mediaRecorder && mediaRecorder.state === 'recording') {
					stopRecording(panel);
				}
			}, MAX_RECORD_MS);
		} catch (err) {
			setStatus(panel, '无法访问麦克风：' + (err.message || err), true);
		}
	}

	async function transcribe(panel, opblock) {
		const token = getBearerToken();
		if (!token) {
			setStatus(panel, '请先在 Swagger 右上角 Authorize 填入 accessToken', true);
			return;
		}
		if (!lastBlob) {
			const fileInput = findFileInput(opblock);
			if (fileInput && fileInput.files && fileInput.files.length > 0) {
				lastBlob = fileInput.files[0];
			}
		}
		if (!lastBlob) {
			setStatus(panel, '请先录音或选择音频文件', true);
			return;
		}
		setStatus(panel, '正在识别…');
		setResult(panel, '');
		const form = new FormData();
		form.append('file', lastBlob, 'recording.webm');
		try {
			const res = await fetch(TRANSCRIBE_PATH, {
				method: 'POST',
				headers: { Authorization: 'Bearer ' + token },
				body: form,
			});
			const body = await res.json().catch(function () {
				return {};
			});
			if (!res.ok) {
				const msg = body.message || body.error || body.title || res.statusText;
				setStatus(panel, '识别失败（' + res.status + '）：' + msg, true);
				return;
			}
			const text = body.text != null ? String(body.text) : '';
			setStatus(panel, '识别成功，可将下方 text 填入 POST /api/v1/ai/chat 的 message');
			setResult(panel, text);
		} catch (err) {
			setStatus(panel, '请求失败：' + (err.message || err), true);
		}
	}

	function mkBtn(cls, label, bg, disabled) {
		const b = document.createElement('button');
		b.type = 'button';
		b.className = cls;
		b.textContent = label;
		b.disabled = !!disabled;
		b.style.cssText =
				'padding:6px 14px;border:none;border-radius:6px;background:'
				+ bg
				+ ';color:#fff;cursor:pointer;';
		if (disabled) {
			b.style.opacity = '0.5';
		}
		return b;
	}

	function wirePanel(panel, opblock) {
		if (panel.dataset.aigpWired === '1') {
			return;
		}
		panel.dataset.aigpWired = '1';
		panel.querySelector('.aigp-btn-record').addEventListener('click', function () {
			startRecording(panel, opblock);
		});
		panel.querySelector('.aigp-btn-stop').addEventListener('click', function () {
			stopRecording(panel);
		});
		panel.querySelector('.aigp-btn-transcribe').addEventListener('click', function () {
			transcribe(panel, opblock);
		});
	}

	function buildPanel(opblock) {
		if (opblock.querySelector('.' + PANEL_CLASS)) {
			return;
		}

		const panel = document.createElement('div');
		panel.className = PANEL_CLASS;

		const boxDiv = document.createElement('div');
		boxDiv.style.cssText =
				'margin:12px 0;padding:12px;border:1px dashed #4ade80;border-radius:8px;background:rgba(74,222,128,0.08);font-size:13px;';

		const titleDiv = document.createElement('div');
		titleDiv.textContent = '🎙 浏览器录音测试';
		titleDiv.style.cssText = 'font-weight:600;color:#4ade80;margin-bottom:8px;';
		boxDiv.appendChild(titleDiv);

		const hintDiv = document.createElement('div');
		hintDiv.innerHTML =
				'Authorize 填入 token → 开始录音 → 停止 → <b>识别并上传</b>（或 Swagger Execute）';
		hintDiv.style.cssText = 'color:#94a3b8;margin-bottom:10px;line-height:1.5;';
		boxDiv.appendChild(hintDiv);

		const btnRowDiv = document.createElement('div');
		btnRowDiv.style.cssText = 'display:flex;flex-wrap:wrap;gap:8px;margin-bottom:8px;';
		btnRowDiv.appendChild(mkBtn('aigp-btn-record', '开始录音', '#22c55e', false));
		btnRowDiv.appendChild(mkBtn('aigp-btn-stop', '停止', '#ef4444', true));
		btnRowDiv.appendChild(mkBtn('aigp-btn-transcribe', '识别并上传', '#3b82f6', false));
		boxDiv.appendChild(btnRowDiv);

		const statusEl = document.createElement('div');
		statusEl.className = 'aigp-speech-status';
		statusEl.textContent = '就绪';
		statusEl.style.cssText = 'color:#94a3b8;margin-bottom:6px;';
		boxDiv.appendChild(statusEl);

		const result = document.createElement('pre');
		result.className = 'aigp-speech-result';
		result.style.cssText =
				'display:none;margin:0;padding:10px;background:#1e293b;color:#e2e8f0;border-radius:6px;white-space:pre-wrap;max-height:200px;overflow:auto;';
		boxDiv.appendChild(result);

		panel.appendChild(boxDiv);

		const section = opblock.querySelector('.opblock-section') || opblock.querySelector('.opblock-body');
		if (section) {
			section.insertBefore(panel, section.firstChild);
		}
		wirePanel(panel, opblock);
	}

	function tryInject() {
		const opblock = findTranscribeOpblock();
		if (!opblock || !opblock.classList.contains('is-open')) {
			return;
		}
		buildPanel(opblock);
	}

	function init() {
		const observer = new MutationObserver(tryInject);
		observer.observe(document.body, { childList: true, subtree: true });
		setInterval(tryInject, 1500);
		tryInject();
	}

	if (document.readyState === 'loading') {
		document.addEventListener('DOMContentLoaded', init);
	} else {
		init();
	}
})();
