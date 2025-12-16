import { useEffect, useRef, useState } from 'react';
import { useAudioStore } from '../store/useAudioStore';
import { clsx } from 'clsx';
import { Edit2, BookOpen, PlayCircle, FileText } from 'lucide-react';

export function Reader() {
  const { 
    sentences, 
    currentSentenceIndex, 
    setText, 
    seek 
  } = useAudioStore();

  const [isEditing, setIsEditing] = useState(true);
  const [inputText, setInputText] = useState("Paste your text here or type something to start...");
  const activeRef = useRef<HTMLSpanElement>(null);

  // Sync state when sentences change (e.g. loaded from Library or Reload)
  useEffect(() => {
    if (sentences.length > 0) {
        setInputText(sentences.join(' '));
        // If we have content from the store, switch to Reader mode
        setIsEditing(false);
    }
  }, [sentences]); // Added dependency to react to Library updates

  useEffect(() => {
    if (activeRef.current && !isEditing) {
      activeRef.current.scrollIntoView({
        behavior: 'smooth',
        block: 'center',
      });
    }
  }, [currentSentenceIndex, isEditing]);

  const handleStart = () => {
    if (!inputText.trim()) return;
    setText(inputText);
    setIsEditing(false);
    useAudioStore.getState().play();
  };

  const handleEdit = () => {
    setInputText(sentences.join(' '));
    setIsEditing(true);
    useAudioStore.getState().pause(); 
  };

  if (isEditing) {
    return (
      <div className="w-full max-w-prose mx-auto">
        <div className="flex justify-between items-center mb-6 px-1">
            <h2 className="text-xl font-bold text-gray-800 flex items-center gap-2">
                <Edit2 size={20} className="text-gray-400" /> 
                <span className="text-gray-500">Editor</span>
            </h2>
            <button 
                onClick={handleStart}
                disabled={!inputText.trim()}
                className="bg-blue-600 text-white px-8 py-3 rounded-full font-bold hover:bg-blue-700 transition shadow-lg shadow-blue-200 hover:shadow-xl active:scale-95 disabled:opacity-50 disabled:shadow-none disabled:cursor-not-allowed flex items-center gap-2"
            >
                <PlayCircle size={20} />
                Start Reading
            </button>
        </div>
        
        {/* Editor Area */}
        <div className="relative group">
            <textarea
                className="w-full h-[60vh] p-8 rounded-3xl border border-gray-200 bg-white focus:ring-4 focus:ring-blue-100 focus:border-blue-500 outline-none resize-none text-lg leading-relaxed shadow-sm transition-all placeholder:text-gray-300 text-gray-700 font-serif"
                value={inputText}
                onChange={(e) => setInputText(e.target.value)}
                placeholder="Paste your text here..."
            />
            {/* Visual hint if empty */}
            {!inputText.trim() && (
                <div className="absolute inset-0 pointer-events-none flex items-center justify-center opacity-0 group-hover:opacity-100 transition-opacity duration-300">
                    <div className="text-gray-400 text-sm font-medium bg-white/90 backdrop-blur px-4 py-2 rounded-full shadow-sm border border-gray-100 flex items-center gap-2">
                        <FileText size={14} />
                        Type or paste text to begin
                    </div>
                </div>
            )}
        </div>
      </div>
    );
  }

  return (
    <div className="w-full max-w-prose mx-auto min-h-[60vh]">
       {/* Reader Header */}
       <div className="flex justify-between items-center mb-6 pb-4 border-b border-gray-100">
            <h2 className="text-xl font-bold text-gray-800 flex items-center gap-2">
                <BookOpen size={20} className="text-blue-600" /> 
                <span>Reader</span>
            </h2>
            <button 
                onClick={handleEdit}
                className="text-gray-500 hover:text-blue-600 px-3 py-1.5 rounded-lg text-sm font-medium transition flex items-center gap-1.5 hover:bg-blue-50/50"
            >
                <Edit2 size={14} /> Edit
            </button>
        </div>

        {/* Text Content */}
        <div className="leading-relaxed text-lg text-gray-700 font-serif tracking-wide py-2">
            {sentences.length === 0 ? (
                <div className="flex flex-col items-center justify-center py-24 text-center">
                    <div className="bg-gray-50 p-4 rounded-full mb-4">
                        <FileText size={32} className="text-gray-300" />
                    </div>
                    <p className="text-gray-400 font-medium">No text to read</p>
                    <button onClick={handleEdit} className="text-blue-500 text-sm mt-2 hover:underline">
                        Return to Editor
                    </button>
                </div>
            ) : (
                sentences.map((sentence, index) => {
                    const isActive = index === currentSentenceIndex;
                    return (
                        <span
                            key={index}
                            ref={isActive ? activeRef : null}
                            onClick={() => seek(index)}
                            className={clsx(
                                "cursor-pointer rounded px-1 transition-colors duration-200 mx-[1px]",
                                isActive 
                                    ? "bg-yellow-100 text-gray-900 font-medium shadow-sm ring-1 ring-yellow-200/50" 
                                    : "hover:bg-gray-100 hover:text-gray-900 text-gray-600"
                            )}
                        >
                            {sentence}{' '}
                        </span>
                    );
                })
            )}
        </div>
    </div>
  );
}