import { Play, Pause, SkipBack, SkipForward, Settings, Loader2 } from 'lucide-react';
import { useAudioStore } from '../store/useAudioStore';
import clsx from 'clsx';
import { useEffect, useState } from 'react';

export function Controls() {
  const { 
    isPlaying, 
    isLoading, 
    play, 
    pause, 
    next, 
    previous, 
    voice, 
    setVoice, 
    speed, 
    setSpeed,
    bufferSize,
    setBufferSize,
    sentences // Get sentences to check for empty state
  } = useAudioStore();

  const [voices, setVoices] = useState<string[]>(['af_sky']);
  const [showSettings, setShowSettings] = useState(false);

  // Fetch voices on mount
  useEffect(() => {
    fetch('http://localhost:3000/v1/audio/voices')
      .then(res => res.json())
      .then(data => setVoices(data.voices))
      .catch(() => console.log('Using default voice list'));
  }, []);

  const togglePlay = () => isPlaying ? pause() : play();
  const hasText = sentences.length > 0;

  return (
    <div className="flex items-center gap-2 sm:gap-4 relative">
        {/* Playback Controls Group - Conditional Styling for Empty State */}
        <div 
            className={clsx(
                "flex items-center gap-1 sm:gap-2 bg-gray-100/80 backdrop-blur rounded-full px-1.5 py-1.5 border border-gray-200 transition-all duration-300",
                !hasText && "opacity-40 grayscale pointer-events-none" // Muted state
            )}
            aria-disabled={!hasText}
        >
            <button 
                onClick={previous} 
                className="p-2.5 text-gray-600 hover:text-black hover:bg-white rounded-full transition active:scale-95 touch-manipulation"
                aria-label="Previous Sentence"
                disabled={!hasText}
            >
                <SkipBack size={20} />
            </button>

            <button 
                onClick={togglePlay} 
                className={clsx(
                    "w-11 h-11 rounded-full text-white shadow-sm flex items-center justify-center transition-all hover:scale-105 active:scale-95 touch-manipulation",
                    isPlaying ? "bg-red-500 hover:bg-red-600" : "bg-blue-600 hover:bg-blue-700"
                )}
                aria-label={isPlaying ? "Pause" : "Play"}
                disabled={!hasText}
            >
                {isLoading ? (
                    <Loader2 size={22} className="animate-spin" /> 
                ) : (
                    isPlaying ? <Pause size={22} fill="currentColor" /> : <Play size={22} fill="currentColor" className="ml-0.5" />
                )}
            </button>

            <button 
                onClick={next} 
                className="p-2.5 text-gray-600 hover:text-black hover:bg-white rounded-full transition active:scale-95 touch-manipulation"
                aria-label="Next Sentence"
                disabled={!hasText}
            >
                <SkipForward size={20} />
            </button>
        </div>

        {/* Settings Button - Always active */}
        <button 
            onClick={() => setShowSettings(!showSettings)} 
            className={clsx(
                "p-2.5 rounded-full transition-colors touch-manipulation",
                showSettings ? "bg-gray-200 text-gray-900" : "text-gray-500 hover:bg-gray-100 hover:text-gray-700"
            )}
            aria-label="Settings"
            aria-expanded={showSettings}
        >
            <Settings size={22} />
        </button>

        {/* Settings Dropdown Panel */}
        {showSettings && (
            <div className="absolute top-full right-0 mt-3 w-72 bg-white rounded-2xl shadow-xl border border-gray-100 p-5 z-50 animate-in fade-in slide-in-from-top-2 origin-top-right">
                <div className="space-y-5">
                    <div>
                        <label className="block text-xs font-bold text-gray-400 mb-2 uppercase tracking-widest">Voice</label>
                        <select 
                            value={voice} 
                            onChange={(e) => setVoice(e.target.value)}
                            className="w-full p-3 rounded-xl border border-gray-200 bg-gray-50 text-sm focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none transition-all appearance-none cursor-pointer"
                        >
                            {voices.map(v => <option key={v} value={v}>{v}</option>)}
                        </select>
                    </div>
                    
                    <div className="grid grid-cols-2 gap-5">
                        <div>
                            <label className="block text-xs font-bold text-gray-400 mb-2 uppercase tracking-widest">Speed ({speed}x)</label>
                            <input 
                                type="range" 
                                min="0.5" 
                                max="2.0" 
                                step="0.1" 
                                value={speed}
                                onChange={(e) => setSpeed(parseFloat(e.target.value))}
                                className="w-full accent-blue-600 h-2 bg-gray-200 rounded-lg appearance-none cursor-pointer"
                            />
                        </div>
                        <div>
                            <label className="block text-xs font-bold text-gray-400 mb-2 uppercase tracking-widest">Buffer ({bufferSize})</label>
                            <input 
                                type="range" 
                                min="2" 
                                max="10" 
                                step="1" 
                                value={bufferSize}
                                onChange={(e) => setBufferSize(parseInt(e.target.value))}
                                className="w-full accent-blue-600 h-2 bg-gray-200 rounded-lg appearance-none cursor-pointer"
                            />
                        </div>
                    </div>
                </div>
                {/* Arrow tip */}
                <div className="absolute -top-1.5 right-4 w-3 h-3 bg-white border-t border-l border-gray-100 transform rotate-45"></div>
            </div>
        )}
    </div>
  );
}