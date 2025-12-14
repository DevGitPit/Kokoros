document.addEventListener('DOMContentLoaded', async () => {
    // --- UI Elements ---
    const textInputEl = document.getElementById('text-input');
    const voiceSelectEl = document.getElementById('voice-select');
    const refreshBtn = document.getElementById('refresh-connection');
    const speedInputEl = document.getElementById('speed-input');
    const speedValueEl = document.getElementById('speed-value');
    const bufferInputEl = document.getElementById('buffer-input');
    
    const streamButton = document.getElementById('stream-button');
    const stopButton = document.getElementById('stop-button');
    const statusMessageEl = document.getElementById('status-message');
    
    const LOCAL_SERVER_URL = 'http://localhost:3000';
    let sentences = [];
    let originalRawText = null; // Store clean text source of truth

    // --- Helpers ---
    function setStatus(message, isError = false) {
        statusMessageEl.textContent = message;
        statusMessageEl.style.color = isError ? '#d32f2f' : '#333';
    }

    function setAppMode(state) {
        const isOffline = state === 'offline';
        const isPlaying = state === 'playing';

        if (isOffline) {
            voiceSelectEl.disabled = true;
        } else {
            voiceSelectEl.disabled = isPlaying;
        }

        speedInputEl.disabled = isOffline || isPlaying;
        bufferInputEl.disabled = isOffline || isPlaying;
        
        // Stream button behaves differently: It is enabled if Paused to allow Resume
        streamButton.disabled = isOffline || isPlaying; 
        
        stopButton.disabled = !isPlaying;
        textInputEl.contentEditable = !isPlaying;
    }

    // Invalidate originalRawText on user edit
    textInputEl.addEventListener('input', () => {
        originalRawText = null;
        sentences = [];
    });

    async function checkServerAndFetchVoices() {
        const originalBtnText = refreshBtn.innerHTML;
        refreshBtn.innerHTML = '<span class="spinning">&#x21bb;</span>';
        try {
            const res = await fetch(`${LOCAL_SERVER_URL}/v1/audio/voices`);
            if (!res.ok) throw new Error('Failed');
            const data = await res.json();
            
            const currentSelection = voiceSelectEl.value;
            voiceSelectEl.innerHTML = '';
            data.voices.forEach(v => {
                const opt = document.createElement('option');
                opt.value = v;
                opt.textContent = v;
                voiceSelectEl.appendChild(opt);
            });
            
            if (data.voices.includes(currentSelection)) voiceSelectEl.value = currentSelection;
            else if (data.voices.includes('af_sky')) voiceSelectEl.value = 'af_sky';
            
            setAppMode('ready');
            setStatus("Connected.");
        } catch (e) {
            setAppMode('offline');
            setStatus("Server offline. Edit text, then click Refresh ↻", true);
        } finally {
            refreshBtn.innerHTML = originalBtnText;
        }
    }

    // --- Highlighting ---
    function highlightSentence(index) {
        if (index < 0 || index >= sentences.length) return;

        const fragment = document.createDocumentFragment();
        sentences.forEach((sentence, i) => {
            const span = document.createElement('span');
            span.textContent = sentence + ' ';
            if (i === index) {
                span.className = 'highlight';
                span.id = 'current-active-sentence';
            }
            fragment.appendChild(span);
        });

        textInputEl.innerHTML = '';
        textInputEl.appendChild(fragment);

        const activeSpan = document.getElementById('current-active-sentence');
        if (activeSpan) {
            activeSpan.scrollIntoView({ behavior: 'smooth', block: 'center', inline: 'nearest' });
        }
    }

    function splitIntoSentences(text) {
        if (!text) return [];
        // Pre-process: Replace newlines with spaces to ensure separation
        const cleanText = text.replace(/[\r\n]+/g, ' ');

        if ('Segmenter' in Intl) {
            const segmenter = new Intl.Segmenter('en', { granularity: 'sentence' });
            return Array.from(segmenter.segment(cleanText)).map(s => s.segment.trim()).filter(s => s.length > 0);
        }
        return cleanText.split(/[.!?]+/).map(s => s.trim()).filter(s => s.length > 0);
    }

    // --- Message Listener ---
    chrome.runtime.onMessage.addListener((msg) => {
        if (msg.type === 'UPDATE_PROGRESS') {
            highlightSentence(msg.index);
        } 
        else if (msg.type === 'PLAYBACK_FINISHED') {
            setAppMode('ready');
            // Restore clean original text (removes spans)
            if (originalRawText) {
                textInputEl.innerText = originalRawText;
            } else {
                textInputEl.textContent = sentences.length > 0 ? sentences.join(' ') : textInputEl.textContent;
            }
            setStatus('Finished / Paused.');
            streamButton.textContent = "Resume Stream"; // Update button text
        } 
        else if (msg.type === 'ERROR') {
            setAppMode('ready');
            setStatus('Error: ' + msg.message, true);
        }
    });

    // --- Button Actions ---
    streamButton.addEventListener('click', () => {
        // If we don't have a clean source of truth, capture it now
        if (originalRawText === null) {
            originalRawText = textInputEl.innerText;
        }
        
        if (!originalRawText.trim()) return setStatus('No text.', true);
        
        // Always refresh sentences from source of truth
        sentences = splitIntoSentences(originalRawText); 

        setAppMode('playing');
        setStatus('Buffering...');

        chrome.runtime.sendMessage({
            type: 'CMD_START_STREAM',
            payload: {
                text: originalRawText, // Send clean text
                voice: voiceSelectEl.value,
                speed: parseFloat(speedInputEl.value),
                bufferTarget: parseInt(bufferInputEl.value) || 2
            }
        });
    });

    stopButton.addEventListener('click', () => {
        chrome.runtime.sendMessage({ type: 'CMD_STOP' });
    });
    
    refreshBtn.addEventListener('click', () => checkServerAndFetchVoices());
    speedInputEl.addEventListener('input', () => speedValueEl.textContent = speedInputEl.value);

    // --- Init ---
    async function init() {
        // Diagnostic: Check for Offscreen support
        try {
            const contexts = await chrome.runtime.getContexts({ contextTypes: ['OFFSCREEN_DOCUMENT'] });
            console.log('Offscreen contexts:', contexts);
            if (contexts.length === 0) {
                console.warn('⚠️ No offscreen document found! Background audio might fail on this browser.');
            }
        } catch (e) {
            console.warn('⚠️ Failed to query offscreen contexts (Browser too old?):', e);
        }

        // Get Text
        const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
        if (tab && !tab.url.startsWith('chrome://')) {
            const res = await chrome.scripting.executeScript({
                target: { tabId: tab.id },
                function: () => {
                    const s = window.getSelection().toString();
                    return s || document.body.innerText.substring(0, 100000); 
                }
            });
            if (res[0]?.result) textInputEl.innerText = res[0].result;
            else textInputEl.innerText = "No text found. Paste here.";
        }
        
        await checkServerAndFetchVoices();
    }
    init();
});
