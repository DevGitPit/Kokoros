import { useState } from 'react';
import { Reader } from './components/Reader';
import { Controls } from './components/Controls';
import { Library } from './components/Library';
import { Headphones, BookOpen } from 'lucide-react';
import clsx from 'clsx';

function App() {
  const [isLibraryOpen, setIsLibraryOpen] = useState(false);

  return (
    <div className="min-h-screen flex flex-col bg-gray-50/50 pb-safe">
      {/* Fixed Header with Controls */}
      <header className="fixed top-0 inset-x-0 z-40 bg-white/90 backdrop-blur-xl border-b border-gray-200/60 shadow-sm transition-all min-h-[4rem] safe-top">
        <div className="max-w-screen-xl mx-auto px-4 w-full flex items-start justify-between gap-4 py-3">
            {/* Left Slot: App Title */}
            <div className="flex items-center gap-3 shrink-0 h-10">
                <div className="bg-gradient-to-tr from-blue-500 to-indigo-600 p-2 rounded-xl text-white shadow-lg shadow-blue-500/20">
                    <Headphones size={20} />
                </div>
                <h1 className="text-lg font-bold text-gray-900 tracking-tight">Kokoros</h1>
            </div>

            {/* Right Slot: Actions & Controls */}
            <div className="flex items-start gap-2 sm:gap-4">
                {/* Library Button */}
                <button 
                    onClick={() => setIsLibraryOpen(true)}
                    className="p-2.5 rounded-full text-gray-500 hover:bg-gray-100 hover:text-blue-600 transition-colors focus:outline-none focus:ring-2 focus:ring-blue-500/20 touch-manipulation"
                    title="Open Library"
                    aria-label="Open Library"
                >
                    <BookOpen size={20} />
                </button>

                {/* Settings & Playback */}
                <Controls />
            </div>
        </div>
      </header>

      {/* Main Content */}
      <main className="flex-grow pt-32 sm:pt-24 pb-12 px-4 sm:px-6 w-full max-w-screen-xl mx-auto">
        <Reader />
      </main>

      {/* Library Overlay */}
      {isLibraryOpen && <Library onClose={() => setIsLibraryOpen(false)} />}
    </div>
  );
}

export default App;
