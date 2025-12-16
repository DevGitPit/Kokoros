import { create } from 'zustand';

// --- Types ---
interface AudioState {
  // State
  isPlaying: boolean;
  isLoading: boolean;
  currentSentenceIndex: number;
  sentences: string[];
  voice: string;
  speed: number;
  bufferSize: number;
  runId: number; 
  
  // Actions
  setText: (text: string) => void;
  setVoice: (voice: string) => void;
  setSpeed: (speed: number) => void;
  setBufferSize: (size: number) => void;
  play: () => void;
  pause: () => void;
  next: () => void;
  previous: () => void;
  seek: (index: number) => void;
}

// --- Audio Context Singleton ---
let audioContext: AudioContext | null = null;
let activeSource: AudioBufferSourceNode | null = null;
let dummyAudio: HTMLAudioElement | null = null;

// Cache to avoid re-fetching recent sentences
const sessionCache = new Map<number, AudioBuffer>();
const inflightRequests = new Map<number, Promise<AudioBuffer | null>>();

// Initialize AudioContext on user interaction
const getAudioContext = () => {
  if (!audioContext) {
    audioContext = new (window.AudioContext || (window as any).webkitAudioContext)({
      sampleRate: 24000 
    });

    // Handle external interruptions (Calls, Alarms, other apps grabbing focus)
    audioContext.onstatechange = () => {
        const state = useAudioStore.getState();
        // Ignore suspension if we are explicitly loading/buffering
        if (audioContext?.state === 'suspended' && state.isPlaying && !state.isLoading) {
             console.log('AudioContext suspended externally (interruption). Pausing UI.');
             useAudioStore.setState({ isPlaying: false, runId: state.runId + 1 });
        }
    };
  }
  return audioContext;
};

// --- Helper: Play Silent Audio for Notification ---
const playSilentAudio = () => {
    if (!dummyAudio) {
        dummyAudio = new Audio();
        // Tiny 1s silent MP3 base64
        dummyAudio.src = 'data:audio/mp3;base64,SUQzBAAAAAAAI1RTSVMAAAAPAAADTGF2ZjU4LjIwLjEwMAAAAAAAAAAAAAAA//OEAAAAAAAAAAAAAAAAAAAAAAAASW5mbwAAAA8AAAAEAAABIADAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMD//////////////////////////////////////////////////////////////////wAAAP//OEAAAAAAAAAAAAAAAAAAAAAAAAMiAAAAAAAAAAAAAAJAAAAAAAAAAABpbmZvAAAADwAAAAQAAAEgAMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDA//////////////////////////////////////////////////////////////////8AAAD/84RAAAAAAAADIAAAAAAAAkAAAAAAAAAAAGxhdmM1OC41NAAAAAAAAAAAAAAAAAAAAAAAJAAAAAAAAAAA//OEQiAAAAAAAyAAAAAAAAACQAAAAAAAAAA=';
        dummyAudio.loop = true;
        dummyAudio.volume = 0.001; // Almost silent, but technically playing
    }
    dummyAudio.play().catch(e => console.warn("Dummy audio failed:", e));
};

const pauseSilentAudio = () => {
    if (dummyAudio) {
        dummyAudio.pause();
    }
};

// --- Helper: Create WAV Header ---
function withWavHeader(pcmData: ArrayBuffer): ArrayBuffer {
    const numChannels = 1;
    const sampleRate = 24000;
    const bitsPerSample = 16; 
    const byteRate = sampleRate * numChannels * (bitsPerSample / 8);
    const blockAlign = numChannels * (bitsPerSample / 8);
    const dataSize = pcmData.byteLength;
    const headerSize = 44;
    const totalSize = headerSize + dataSize;

    const buffer = new ArrayBuffer(totalSize);
    const view = new DataView(buffer);

    writeString(view, 0, 'RIFF');
    view.setUint32(4, 36 + dataSize, true); 
    writeString(view, 8, 'WAVE');
    writeString(view, 12, 'fmt ');
    view.setUint32(16, 16, true); 
    view.setUint16(20, 1, true); 
    view.setUint16(22, numChannels, true); 
    view.setUint32(24, sampleRate, true); 
    view.setUint32(28, byteRate, true); 
    view.setUint16(32, blockAlign, true); 
    view.setUint16(34, bitsPerSample, true); 
    writeString(view, 36, 'data');
    view.setUint32(40, dataSize, true); 

    const pcmBytes = new Uint8Array(pcmData);
    const newBytes = new Uint8Array(buffer);
    newBytes.set(pcmBytes, 44);

    return buffer;
}

function writeString(view: DataView, offset: number, string: string) {
    for (let i = 0; i < string.length; i++) {
        view.setUint8(offset + i, string.charCodeAt(i));
    }
}

// --- Helper: Setup Media Session Handlers ---
const setupMediaHandlers = (get: () => AudioState) => {
    if (!('mediaSession' in navigator)) return;
    
    const handlers = [
        ['play', () => get().play()],
        ['pause', () => get().pause()],
        ['stop', () => get().pause()],
        ['previoustrack', () => get().previous()],
        ['nexttrack', () => get().next()]
    ] as const;

    handlers.forEach(([action, handler]) => {
        try { navigator.mediaSession.setActionHandler(action, handler); } catch(e) {}
    });
};

// --- Store ---
export const useAudioStore = create<AudioState>((set, get) => {
  
  // --- Internal: Fetch Logic ---
  const fetchAudio = async (text: string, index: number): Promise<AudioBuffer | null> => {
    const { voice, speed } = get();
    if (sessionCache.has(index)) return sessionCache.get(index) || null;
    if (inflightRequests.has(index)) return inflightRequests.get(index) || null;

    console.log(`fetching index ${index}...`);

    const promise = (async () => {
        try {
            const response = await fetch('http://localhost:3000/v1/audio/speech', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    model: 'tts-1',
                    input: text,
                    voice,
                    speed,
                    response_format: 'pcm' 
                })
            });

            if (!response.ok) throw new Error('Network response was not ok');
            const rawPcmData = await response.arrayBuffer();
            const wavBuffer = withWavHeader(rawPcmData);
            const ctx = getAudioContext();
            const audioBuffer = await ctx.decodeAudioData(wavBuffer);
            
            sessionCache.set(index, audioBuffer);
            if (sessionCache.size > 20) {
                const firstKey = sessionCache.keys().next().value;
                if (firstKey && firstKey < index - 5) sessionCache.delete(firstKey);
            }
            return audioBuffer;
        } catch (error) {
            console.error("Fetch/Decode failed:", error);
            return null;
        } finally {
            inflightRequests.delete(index);
        }
    })();

    inflightRequests.set(index, promise);
    return promise;
  };

  // --- Internal: Play Loop ---
  const playQueue = async (startIndex: number, thisRunId: number) => {
    const ctx = getAudioContext();
    if (ctx.state === 'suspended') await ctx.resume();

    // 1. Race Condition Check
    if (get().runId !== thisRunId) return;

    const { sentences, isPlaying, bufferSize } = get();
    if (!isPlaying || startIndex >= sentences.length) {
      set({ isPlaying: false });
      return;
    }

    set({ isLoading: true });

    // Dynamic Prefetch - FIRE IMMEDIATELY (Do not await current sentence first)
    for (let i = 1; i <= bufferSize; i++) {
        const nextIndex = startIndex + i;
        if (nextIndex < sentences.length) {
            // This is now deduplicated by fetchAudio/inflightRequests
            fetchAudio(sentences[nextIndex], nextIndex);
        }
    }

    const currentBuffer = await fetchAudio(sentences[startIndex], startIndex);
    
    // 2. Race Condition Check
    if (get().runId !== thisRunId) return;

    if (!currentBuffer) {
      console.warn(`Skipping sentence ${startIndex}`);
      playQueue(startIndex + 1, thisRunId);
      return;
    }

    set({ isLoading: false, currentSentenceIndex: startIndex });
    
    if (activeSource) {
        try { activeSource.stop(); } catch(e) {}
    }

    activeSource = ctx.createBufferSource();
    activeSource.buffer = currentBuffer;
    activeSource.connect(ctx.destination);
    
    // Setup Media Session Metadata
    if ('mediaSession' in navigator) {
        navigator.mediaSession.playbackState = 'playing';
        try {
            navigator.mediaSession.setPositionState({
                duration: currentBuffer.duration,
                playbackRate: 1.0,
                position: 0
            });
        } catch (e) {}
    }

    activeSource.onended = () => {
      if (get().runId === thisRunId && get().isPlaying) {
        playQueue(startIndex + 1, thisRunId);
      }
    };

    activeSource.start();
  };

  return {
    isPlaying: false,
    isLoading: false,
    currentSentenceIndex: 0,
    sentences: [],
    voice: 'af_sky',
    speed: 1.0,
    bufferSize: 2, 
    runId: 0,

    setText: (text) => {
      if (activeSource) { try { activeSource.stop(); } catch(e) {} }
      
      const cleanText = text.replace(new RegExp('[\r\n]+', 'g'), ' ');
      let segs: string[] = [];
      
      if ('Segmenter' in Intl) {
          const segmenter = new Intl.Segmenter('en', { granularity: 'sentence' });
          segs = Array.from(segmenter.segment(cleanText)).map(s => s.segment.trim()).filter(s => s.length > 0);
      } else {
          segs = cleanText.split(/[.!?]+/).map(s => s.trim()).filter(s => s.length > 0);
      }
      
      set((state) => ({
          sentences: segs, 
          currentSentenceIndex: 0, 
          isPlaying: false,
          runId: state.runId + 1 
      }));
    },

    setVoice: (voice) => {
      set({ voice });
      sessionCache.clear();
    },
    
    setSpeed: (speed) => {
      set({ speed });
      sessionCache.clear();
    },

    setBufferSize: (bufferSize) => {
        set({ bufferSize });
    },

    play: () => {
      const ctx = getAudioContext();
      if (ctx.state === 'suspended') ctx.resume();
      
      setupMediaHandlers(get);
      playSilentAudio(); // Trigger notification

      set((state) => ({ isPlaying: true, runId: state.runId + 1 }));

      const { currentSentenceIndex, runId } = get();
      playQueue(currentSentenceIndex, runId);
    },

    pause: () => {
      set((state) => ({ isPlaying: false, runId: state.runId + 1 }));
      
      if (activeSource) {
        try { activeSource.stop(); } catch(e) {}
        activeSource = null;
      }
      pauseSilentAudio(); // Release notification hold
      const ctx = getAudioContext();
      ctx.suspend();
      
      if ('mediaSession' in navigator) {
          navigator.mediaSession.playbackState = 'paused';
      }
    },

    next: () => {
       const { currentSentenceIndex, sentences } = get();
       if (currentSentenceIndex < sentences.length - 1) {
          get().seek(currentSentenceIndex + 1);
       }
    },

    previous: () => {
       const { currentSentenceIndex } = get();
       if (currentSentenceIndex > 0) {
          get().seek(currentSentenceIndex - 1);
       }
    },

    seek: (index) => {
        if (activeSource) { try { activeSource.stop(); } catch(e) {} }
        
        set((state) => ({
            currentSentenceIndex: index, 
            isPlaying: true, 
            runId: state.runId + 1 
        }));

        const { runId } = get();
        playQueue(index, runId);
    }
  };
});