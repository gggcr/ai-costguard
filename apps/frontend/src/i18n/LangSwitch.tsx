import { useEffect, useState } from 'react';
import { getLang, onLang, setLang, type Lang } from './index';

/** RU | EN toggle. The whole interface, including data that arrives later, follows it. */
export function LangSwitch() {
  const [lang, set] = useState<Lang>(getLang());
  useEffect(() => onLang(set), []);
  return (
    <div className="lang-switch" role="group" aria-label="Language" data-no-translate>
      {(['ru', 'en'] as const).map((l) => (
        <button key={l} type="button" className={lang === l ? 'on' : ''} aria-pressed={lang === l} onClick={() => setLang(l)}>
          {l.toUpperCase()}
        </button>
      ))}
    </div>
  );
}
