document.addEventListener('DOMContentLoaded', async () => {
    // UI Elements
    const textInputEl = document.getElementById('text-input');
    const voiceSelectEl = document.getElementById('voice-select');
    const speedInputEl = document.getElementById('speed-input');
    const speedValueEl = document.getElementById('speed-value');
    const bufferInputEl = document.getElementById('buffer-input');
    const speakButton = document.getElementById('speak-button');
    const streamButton = document.getElementById('stream-button');
    const stopButton = document.getElementById('stop-button');
    const statusMessageEl = document.getElementById('status-message');
    const audioPlayer = document.getElementById('audio-player');
    const notificationSound = document.getElementById('notification-sound');

    const LOCAL_SERVER_URL = 'http://localhost:3000';
    
    // State
    let audioContext = null;
    let streamAbortController = null;
    let sentences = [];
    let currentSentenceIndex = 0;
    
    // Flags
    let isPlaying = false;
    let isPaused = false;
    let isStreaming = false;
    
    // Queue State
    let audioQueue = [];
    let nextStartTime = 0;
    
    // --- Utility Functions ---
    function setStatus(message, isError = false) {
        statusMessageEl.textContent = message;
        statusMessageEl.style.color = isError ? 'red' : (isError === false ? 'green' : '#495057');
    }

    function disableControls(disable) {
        speakButton.disabled = disable;
        // streamButton should be enabled if we are paused to allow resume
        streamButton.disabled = disable && !isPaused; 
        voiceSelectEl.disabled = disable;
        speedInputEl.disabled = disable;
        textInputEl.contentEditable = !disable;
        bufferInputEl.disabled = disable;
    }

    function splitIntoSentences(text) {
        if (!text) return [];
        const segmenter = new Intl.Segmenter('en', { granularity: 'sentence' });
        const segments = segmenter.segment(text);
        return Array.from(segments)
            .map(s => s.segment.replace(/\r\n/g, ' ').replace(/\n/g, ' ').trim())
            .filter(s => s.length > 0);
    }

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

        const el = document.getElementById('current-active-sentence');
        if (el) {
            el.scrollIntoView({ behavior: 'smooth', block: 'center' });
        }
    }

    // --- Audio Context Helpers ---
    function initAudioContext() {
        const AudioCtor = window.AudioContext || window.webkitAudioContext;
        if (!audioContext || audioContext.state === 'closed') {
            audioContext = new AudioCtor({ sampleRate: 24000 });
        }
        if (audioContext.state === 'suspended') {
            audioContext.resume();
        }
    }

    function playNotification() {
        if (notificationSound) {
            notificationSound.play().catch(() => {});
        }
    }

    // --- Stop/Reset ---
    async function stopAll(resetIndex = false) {
        if (streamAbortController) {
            streamAbortController.abort();
            streamAbortController = null;
        }
        
        if (audioContext) {
            try { await audioContext.close(); } catch(e){}
            audioContext = null;
        }

        audioPlayer.pause();
        
        // Don't clear src immediately if we are just pausing Full mode
        // But for consistency with "Stop" button, we usually clear.
        if (resetIndex) {
            audioPlayer.src = '';
            audioPlayer.currentTime = 0;
            currentSentenceIndex = 0;
            // Clear highlight?
            textInputEl.textContent = sentences.length > 0 ? sentences.join(' ') : textInputEl.textContent;
            setStatus('Stopped.');
        } else {
             // Paused state
             setStatus('Paused.');
        }

        isPlaying = false;
        isPaused = !resetIndex; // If not resetting index, we are paused
        isStreaming = false;
        audioQueue = [];
        
        disableControls(false);
        stopButton.disabled = true;
        
        // Update Button Text to reflect state
        streamButton.textContent = isPaused ? 'Resume Stream' : 'Stream';
        
        updateMediaSession(isPaused ? 'paused' : 'none');
    }

    // --- Media Session ---
    function updateMediaSession(state) {
        if (!('mediaSession' in navigator)) return;
        navigator.mediaSession.playbackState = state;
        
        if (state === 'playing' || state === 'paused') {
            navigator.mediaSession.metadata = new MediaMetadata({
                title: 'Kokoro Reader',
                artist: 'Local TTS',
                album: isStreaming ? `Sentence ${currentSentenceIndex + 1}/${sentences.length}` : 'Full Audio',
                artwork: [{ src: 'icons/icon128.png', sizes: '128x128', type: 'image/png' }]
            });
        }
    }

    function setupMediaSession() {
        if (!('mediaSession' in navigator)) return;

        navigator.mediaSession.setActionHandler('play', () => {
             if (isPaused && isStreaming) {
                 speakStream(true); 
             } else if (audioPlayer.src) {
                 audioPlayer.play();
             }
        });
        navigator.mediaSession.setActionHandler('pause', () => {
            if (isStreaming) {
                stopAll(false);
            } else {
                audioPlayer.pause();
            }
        });
        navigator.mediaSession.setActionHandler('stop', () => stopAll(true));
        navigator.mediaSession.setActionHandler('seekto', (details) => {
             if (!isStreaming && audioPlayer.src) {
                 audioPlayer.currentTime = details.seekTime;
             }
        });
        navigator.mediaSession.setActionHandler('previoustrack', () => {
             if (isStreaming || (isPaused && sentences.length > 0)) {
                 stopAll(false);
                 currentSentenceIndex = Math.max(0, currentSentenceIndex - 1);
                 speakStream(true);
             }
        });
        navigator.mediaSession.setActionHandler('nexttrack', () => {
            if (isStreaming || (isPaused && sentences.length > 0)) {
                 stopAll(false);
                 currentSentenceIndex++;
                 speakStream(true);
            }
        });
    }

    // --- Speak Full ---
    async function speakFull() {
        const text = textInputEl.innerText;
        const voice = voiceSelectEl.value;
        const speed = parseFloat(speedInputEl.value);

        if (!text.trim()) { setStatus('No text.', true); return; }

        // Reset state
        await stopAll(true);
        disableControls(true);
        stopButton.disabled = false;
        
        setStatus('Generating full audio... (Please wait)');
        updateMediaSession('playing');

        try {
            const response = await fetch(`${LOCAL_SERVER_URL}/v1/audio/speech`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    model: 'tts-1',
                    input: text,
                    voice: voice,
                    speed: speed,
                    stream: false,
                    response_format: 'mp3'
                })
            });

            if (response.ok) {
                const blob = await response.blob();
                const url = URL.createObjectURL(blob);
                audioPlayer.src = url;
                audioPlayer.style.display = 'block'; // Ensure controls are visible
                audioPlayer.play().catch(e => console.error("Play failed", e));
                setStatus('Playing full audio.');
                
                audioPlayer.onended = () => {
                    playNotification();
                    stopAll(true);
                };
            } else {
                setStatus('Server error.', true);
                stopAll(true);
            }
        } catch (e) {
            setStatus('Network error.', true);
            stopAll(true);
        }
    }

    // --- Speak Stream ---
    async function speakStream(resume = false) {
        const text = textInputEl.innerText;
        const voice = voiceSelectEl.value;
        const speed = parseFloat(speedInputEl.value);
        const bufferTarget = Math.max(1, parseInt(bufferInputEl.value) || 2);

        // Logic fix: correctly handle resume
        if (!resume) {
            sentences = splitIntoSentences(text);
            currentSentenceIndex = 0;
        } else {
            // Resume: Start from context logic
            // If we paused at index 5, start at 4 to give context?
            currentSentenceIndex = Math.max(0, currentSentenceIndex - 1);
        }

        if (sentences.length === 0 || currentSentenceIndex >= sentences.length) {
            setStatus('Finished / No text.');
            return;
        }

        if (audioContext) await audioContext.close();
        
        isStreaming = true;
        isPlaying = true;
        isPaused = false;
        audioQueue = [];
        
        disableControls(true);
        stopButton.disabled = false;
        streamButton.textContent = 'Stream'; // Reset text
        
        initAudioContext();
        nextStartTime = audioContext.currentTime + 0.1;
        
        streamAbortController = new AbortController();
        const signal = streamAbortController.signal;

        setStatus(`Streaming...`);
        updateMediaSession('playing');
        highlightSentence(currentSentenceIndex);

        let fetchIndex = currentSentenceIndex;
        let playIndex = currentSentenceIndex;
        let isFetching = false;
        
        const checkLoop = async () => {
            if (signal.aborted) return;
            
            // 1. Play
            while (audioQueue.length > 0) {
                const item = audioQueue.shift();
                scheduleBuffer(item);
                playIndex++;
            }

            // 2. Fetch
            const inFlightOrBuffered = fetchIndex - playIndex;
            if (!isFetching && inFlightOrBuffered < bufferTarget && fetchIndex < sentences.length) {
                isFetching = true;
                const indexToFetch = fetchIndex;
                fetchIndex++;
                
                fetchSentence(sentences[indexToFetch], voice, speed, signal)
                    .then(buffer => {
                        if (signal.aborted) return;
                        if (buffer) {
                            audioQueue.push({ buffer, index: indexToFetch });
                        }
                    })
                    .finally(() => {
                        isFetching = false;
                        checkLoop();
                    });
            }

            // 3. Complete
            if (playIndex >= sentences.length && !isFetching && audioQueue.length === 0) {
                if (audioContext && audioContext.currentTime > nextStartTime) {
                    playNotification();
                    stopAll(true);
                    setStatus('Finished.');
                    return;
                }
            }

            if (isStreaming) setTimeout(checkLoop, 100);
        };

        checkLoop();
    }

    async function fetchSentence(text, voice, speed, signal) {
        try {
            const res = await fetch(`${LOCAL_SERVER_URL}/v1/audio/speech`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    model: 'tts-1',
                    input: text,
                    voice: voice,
                    speed: speed,
                    stream: false,
                    response_format: 'mp3'
                }),
                signal: signal
            });
            if (!res.ok) throw new Error('Err');
            const ab = await res.arrayBuffer();
            return await audioContext.decodeAudioData(ab);
        } catch (e) { return null; }
    }

    function scheduleBuffer(item) {
        if (!audioContext) return;
        const source = audioContext.createBufferSource();
        source.buffer = item.buffer;
        source.connect(audioContext.destination);
        
        const startTime = Math.max(audioContext.currentTime, nextStartTime);
        source.start(startTime);
        nextStartTime = startTime + item.buffer.duration;
        
        const delay = (startTime - audioContext.currentTime) * 1000;
        setTimeout(() => {
            if (isStreaming && !isPaused) {
                highlightSentence(item.index);
                currentSentenceIndex = item.index;
                // Update notification for lock screen
                if ('mediaSession' in navigator) {
                     navigator.mediaSession.metadata.album = `Sentence ${item.index + 1}/${sentences.length}`;
                }
            }
        }, delay);
    }

    async function init() {
        const text = await getSelectedText();
        if (text) textInputEl.innerText = text;
        await fetchVoices();
        setupMediaSession();
        stopButton.disabled = true;
    }

    async function getSelectedText() {
        try {
            const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
            if (!tab) return '';
            const res = await chrome.scripting.executeScript({
                target: { tabId: tab.id },
                function: () => {
                    const s = window.getSelection().toString();
                    return s || document.body.innerText;
                }
            });
            return res[0]?.result?.substring(0, 50000) || '';
        } catch { return ''; }
    }

    async function fetchVoices() {
        try {
            const res = await fetch(`${LOCAL_SERVER_URL}/v1/audio/voices`);
            const data = await res.json();
            voiceSelectEl.innerHTML = '';
            data.voices.forEach(v => {
                const opt = document.createElement('option');
                opt.value = v;
                opt.textContent = v;
                voiceSelectEl.appendChild(opt);
            });
            if (data.voices.includes('af_sky')) voiceSelectEl.value = 'af_sky';
        } catch (e) {}
    }

    // --- Button Handlers ---
    speakButton.addEventListener('click', speakFull);
    
    // Correct Resume Logic
    streamButton.addEventListener('click', () => {
        if (isPaused) {
            speakStream(true); // Resume
        } else {
            speakStream(false); // New Start
        }
    });
    
    stopButton.addEventListener('click', () => {
        if (isStreaming) {
             stopAll(false); // Pause
        } else {
             stopAll(true); // Stop Full
        }
    });

    speedInputEl.addEventListener('input', () => speedValueEl.textContent = speedInputEl.value);

    // Audio Element Event
    audioPlayer.addEventListener('play', () => {
        updateMediaSession('playing');
        setStatus('Playing full audio.');
    });
    audioPlayer.addEventListener('pause', () => {
        updateMediaSession('paused');
        setStatus('Paused.');
    });

    init();
});