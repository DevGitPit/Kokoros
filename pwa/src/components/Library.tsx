import { useState } from 'react';
import ePub from 'epubjs';
import { X, Upload, Book, FileText, Loader2, ChevronRight, BookOpen } from 'lucide-react';
import { useAudioStore } from '../store/useAudioStore';
import clsx from 'clsx';

interface LibraryProps {
  onClose: () => void;
}

interface Chapter {
  id: string;
  label: string;
  href: string;
}

export function Library({ onClose }: LibraryProps) {
  const { setText } = useAudioStore();
  const [toc, setToc] = useState<Chapter[]>([]);
  const [bookTitle, setBookTitle] = useState('');
  const [author, setAuthor] = useState('');
  const [coverUrl, setCoverUrl] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [book, setBook] = useState<any>(null); // epubjs Book object

  const handleFileUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;

    setIsLoading(true);
    try {
      const arrayBuffer = await file.arrayBuffer();
      const newBook = ePub(arrayBuffer);
      
      await newBook.ready;
      const metadata = await newBook.loaded.metadata;
      const navigation = await newBook.loaded.navigation;
      
      // Get cover
      try {
        const coverUrl = await newBook.coverUrl();
        setCoverUrl(coverUrl);
      } catch (e) {
        console.warn('No cover found');
      }

      // Flatten navigation (Handle nested TOCs simply for now)
      const flatten = (items: any[]): Chapter[] => {
        return items.reduce((acc, item) => {
           acc.push({ id: item.id, label: item.label, href: item.href });
           if (item.subitems && item.subitems.length > 0) {
               acc.push(...flatten(item.subitems));
           }
           return acc;
        }, []);
      };

      const chapters = flatten(navigation.toc);

      setBook(newBook);
      setBookTitle(metadata.title);
      setAuthor(metadata.creator);
      setToc(chapters);
    } catch (err) {
      console.error('Failed to load EPUB:', err);
      alert('Failed to load EPUB file.');
    } finally {
      setIsLoading(false);
    }
  };

  const loadChapter = async (chapter: Chapter) => {
    if (!book) return;
    setIsLoading(true);

    try {
        const cleanHref = chapter.href.split('#')[0];
        console.log(`Loading chapter via book.load() (Headless): ${cleanHref} (orig: ${chapter.href})`);

        const raw = await book.load(cleanHref);

        if (!raw) {
            throw new Error('book.load() returned empty');
        }

        let doc: Document;

        if (raw instanceof Document || (typeof raw === 'object' && raw.documentElement)) {
             console.log("book.load() returned a Document directly.");
             doc = raw as Document;
        } else if (typeof raw === 'string') {
             console.log("book.load() returned a string. Parsing...");
             const parser = new DOMParser();
             doc = parser.parseFromString(raw, 'application/xhtml+xml');
             
             const errorNode = doc.querySelector('parsererror');
             if (errorNode) {
                 console.warn("XHTML parse failed, falling back to HTML...", errorNode.textContent);
                 doc = parser.parseFromString(raw, 'text/html');
             }
        } else {
             throw new Error(`book.load() returned unknown type: ${typeof raw}`);
        }

        return extractTextFromDocument(doc);

    } catch (err) {
        console.error('Chapter extraction failed:', err);
        let msg = String(err);
        if (typeof err === 'object' && err !== null && (err as any).message) {
            msg = (err as any).message;
        } else if (typeof err === 'object' && err !== null) {
            msg = JSON.stringify(err);
        }
        alert('Could not extract text: ' + msg);
    } finally {
        setIsLoading(false);
    }
  };

  // Helper function to extract text and set state
  const extractTextFromDocument = (doc: Document) => {
    const root = doc.body || doc.documentElement;

    if (!root) {
      throw new Error('No root element in parsed chapter');
    }

    const extracted = root.textContent;

    if (typeof extracted !== 'string') {
      throw new Error('Extracted content is not a string (typeof ' + typeof extracted + ')');
    }

    const finalText = extracted.replace(/\s+/g, ' ').trim();

    if (!finalText) {
      throw new Error('Final extracted text is empty');
    }

    // FATAL SANITY CHECK
    if (typeof finalText !== 'string') {
        throw new Error('FATAL: finalText is not string, it is ' + typeof finalText);
    }

    console.log(`Extracted ${finalText.length} characters (final check)`);
    setText(finalText);
    onClose();
  };

  const reset = () => {
    setBook(null);
    setToc([]);
    setBookTitle('');
    setAuthor('');
    setCoverUrl(null);
  };

  return (
    <div className="fixed inset-0 z-[60] bg-gray-50/95 backdrop-blur-sm flex items-center justify-center p-4 animate-in fade-in duration-200">
        <div className="bg-white w-full max-w-md md:max-w-2xl h-[85vh] rounded-2xl shadow-2xl flex flex-col border border-gray-200 overflow-hidden">
            
            {/* Header */}
            <div className="flex items-center justify-between p-4 border-b border-gray-100 bg-white sticky top-0 z-10">
                <div className="flex items-center gap-2">
                    <Book className="text-blue-600" size={20} />
                    <h2 className="text-lg font-bold text-gray-800">Library</h2>
                </div>
                <button onClick={onClose} className="p-2 hover:bg-gray-100 rounded-full text-gray-500 transition">
                    <X size={20} />
                </button>
            </div>

            {/* Content */}
            <div className="flex-1 overflow-y-auto p-0 bg-gray-50">
                {!book ? (
                    // Upload State
                    <div className="h-full flex flex-col items-center justify-center p-8 text-center">
                        <div className="w-20 h-20 bg-blue-50 rounded-full flex items-center justify-center mb-6">
                            <Upload className="text-blue-500" size={32} />
                        </div>
                        <h3 className="text-xl font-bold text-gray-900 mb-2">Open EPUB Book</h3>
                        <p className="text-gray-500 mb-8 max-w-xs mx-auto">Select an .epub file from your device to start reading.</p>
                        
                        <label className="bg-blue-600 text-white px-8 py-3 rounded-full font-semibold hover:bg-blue-700 transition shadow-lg shadow-blue-200 cursor-pointer active:scale-95 flex items-center gap-2">
                             {isLoading ? <Loader2 className="animate-spin" size={20} /> : <BookOpen size={20} />}
                             <span>Select File</span>
                             <input type="file" accept=".epub" onChange={handleFileUpload} className="hidden" />
                        </label>
                    </div>
                ) : (
                    // TOC State
                    <div className="min-h-full bg-white">
                        {/* Book Metadata */}
                        <div className="p-6 bg-gray-50 border-b border-gray-100 flex gap-4 items-start">
                             {coverUrl ? (
                                 <img src={coverUrl} alt="Cover" className="w-20 h-28 object-cover rounded shadow-sm bg-gray-200" />
                             ) : (
                                 <div className="w-20 h-28 bg-gray-200 rounded flex items-center justify-center shadow-sm">
                                     <Book size={32} className="text-gray-400" />
                                 </div>
                             )}
                             <div className="flex-1 min-w-0">
                                 <h3 className="text-lg font-bold text-gray-900 leading-tight mb-1">{bookTitle || 'Untitled Book'}</h3>
                                 <p className="text-sm text-gray-500 mb-4">{author || 'Unknown Author'}</p>
                                 <button onClick={reset} className="text-xs font-semibold text-blue-600 hover:text-blue-700 hover:underline">
                                     Open Different Book
                                 </button>
                             </div>
                        </div>

                        {/* Chapter List */}
                        <div className="p-2">
                            <h4 className="text-xs font-bold text-gray-400 uppercase tracking-widest px-4 py-3">Chapters</h4>
                            {isLoading ? (
                                <div className="flex justify-center py-10">
                                    <Loader2 className="animate-spin text-blue-500" size={32} />
                                </div>
                            ) : (
                                <div className="space-y-1">
                                    {toc.length > 0 ? toc.map((chapter) => (
                                        <button 
                                            key={chapter.id} 
                                            onClick={() => loadChapter(chapter)}
                                            className="w-full text-left px-4 py-3 hover:bg-blue-50 rounded-lg flex items-center justify-between group transition-colors"
                                        >
                                            <div className="flex items-center gap-3 overflow-hidden">
                                                <div className="w-8 h-8 rounded-full bg-gray-100 flex items-center justify-center shrink-0 text-gray-500 group-hover:bg-blue-100 group-hover:text-blue-600 transition-colors">
                                                    <FileText size={14} />
                                                </div>
                                                <span className="text-sm font-medium text-gray-700 group-hover:text-blue-900 truncate pr-4">
                                                    {chapter.label.trim() || 'Untitled Chapter'}
                                                </span>
                                            </div>
                                            <ChevronRight size={16} className="text-gray-300 group-hover:text-blue-400 shrink-0" />
                                        </button>
                                    )) : (
                                        <div className="text-center py-10 text-gray-400">
                                            No chapters found.
                                        </div>
                                    )}
                                </div>
                            )}
                        </div>
                    </div>
                )}
            </div>
        </div>
    </div>
  );
}
