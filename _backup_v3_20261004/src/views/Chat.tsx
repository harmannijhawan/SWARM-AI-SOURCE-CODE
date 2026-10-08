import { useEffect, useRef, useState } from 'react';
import { ArrowUp, MessageSquarePlus, Paperclip, Search, Square, X } from 'lucide-react';
import type { ChatAttachment, ChatTurn, Conversation } from '../../shared/chat';
import { chatApi, useChat } from '../lib/chat';
import { api } from '../lib/api';
import { useStore } from '../lib/store';
import { ChatMarkdown } from '../components/ChatMarkdown';
import { LogoMark } from '../components/Logo';

const fail = (e: unknown) => useChat.setState({ error: e instanceof Error ? e.message : String(e) });
function groupDate(ts: number) {
  const today = new Date(); today.setHours(0,0,0,0);
  const day = new Date(ts); day.setHours(0,0,0,0);
  const age = Math.round((today.getTime()-day.getTime())/86400000);
  return age === 0 ? 'Today' : age === 1 ? 'Yesterday' : age < 7 ? 'Previous 7 days' : 'Older';
}
export function ChatSidebar() {
  const { list, current } = useChat();
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<string[]>([]);
  const [rename, setRename] = useState<string | null>(null);
  const [title, setTitle] = useState('');
  useEffect(() => {
    if (!query.trim()) { setResults([]); return; }
    let live = true;
    const timer = setTimeout(() => { void chatApi.search(query).then(ids => { if (live) setResults(ids); }).catch(fail); }, 140);
    return () => { live = false; clearTimeout(timer); };
  }, [query, list]);
  return <aside className="chat-sidebar"><div className="chat-brand drag"><LogoMark size={22}/><strong>SWARM</strong></div>
    <button className="chat-new" onClick={() => { useStore.getState().setMode('CHAT'); void useChat.getState().fresh().catch(fail); }}><MessageSquarePlus size={16}/> New chat</button>
    <label className="chat-search"><Search size={14}/><input aria-label="Search chats" placeholder="Search chats" value={query} onChange={e=>setQuery(e.target.value)}/></label>
    <div className="chat-history">{['Today','Yesterday','Previous 7 days','Older'].map(group => {
      const chats = list.filter(c=>groupDate(c.updatedAt)===group && (!query.trim() || results.includes(c.id)));
      return chats.length > 0 && <section key={group}><h2>{group}</h2>{chats.map(c=><div key={c.id} className="chat-history-row" data-selected={current?.id===c.id}>
        {rename===c.id ? <form onSubmit={e=>{ e.preventDefault(); void chatApi.rename(c.id,title).then(updated=>{useChat.getState().ingest(updated);setRename(null);}).catch(fail); }}><input aria-label="Chat name" autoFocus value={title} onChange={e=>setTitle(e.target.value)} onKeyDown={e=>{if(e.key==='Escape')setRename(null);}}/><button>Save</button></form> : <><button className="chat-history-title" onClick={()=>{useStore.getState().setMode('CHAT');void useChat.getState().open(c.id).catch(fail);}}>{c.title}</button><button aria-label={`Rename ${c.title}`} onClick={()=>{setRename(c.id);setTitle(c.title);}}>⋯</button><button aria-label={`Delete ${c.title}`} onClick={()=>{void chatApi.remove(c.id).then(()=>{if(current?.id===c.id)useChat.setState({current:null});return useChat.getState().refresh();}).catch(fail);}}><X size={12}/></button></>}
      </div>)}</section>;
    })}</div>
    <div className="chat-sidebar-footer"><button onClick={()=>useStore.getState().setView('models')}>Models</button><button onClick={()=>useStore.getState().setView('settings')}>Settings</button><button onClick={()=>useStore.getState().setMode('BUILD')}>Build projects</button></div>
  </aside>;
}

export function Chat() {
  const { current, error } = useChat();
  const models = useStore(s=>s.models);
  const [text,setText] = useState('');
  const [attachments,setAttachments] = useState<ChatAttachment[]>([]);
  const [model,setModel] = useState('');
  const [copied,setCopied] = useState<string | null>(null);
  const [sending,setSending] = useState(false);
  const [editing,setEditing] = useState<string | undefined>();
  const [objective,setObjective] = useState<string | null>(null);
  const [building,setBuilding] = useState(false);
  const scroll = useRef<HTMLDivElement>(null);
  const stick = useRef(true);
  const input = useRef<HTMLTextAreaElement>(null);
  const history = useRef<{ id?: string; ids: Set<string> }>({ ids: new Set() });
  if (history.current.id !== current?.id) history.current = { id: current?.id, ids: new Set(current?.turns.map(t => t.id) ?? []) };
  const running = current?.turns.some(t=>t.status==='streaming') ?? false;
  useEffect(()=>{
    const off=window.swarm.on('chat:updated',c=>useChat.getState().ingest(c as Conversation));
    void useChat.getState().refresh().then(async()=>{const id=localStorage.getItem('swarm:chat');if(id && useChat.getState().list.some(c=>c.id===id))await useChat.getState().open(id);}).catch(fail);
    const prefill=(e:Event)=>{setText((e as CustomEvent<string>).detail); input.current?.focus();};
    window.addEventListener('swarm:chat-prefill',prefill);
    return ()=>{off();window.removeEventListener('swarm:chat-prefill',prefill);};
  },[]);
  useEffect(()=>{if(stick.current && scroll.current)scroll.current.scrollTop=scroll.current.scrollHeight;},[current]);
  useEffect(()=>{stick.current=true;setEditing(undefined);setText('');setAttachments([]);},[current?.id]);
  const send=async(value=text,retryFrom=editing,files=attachments)=>{
    if(!value.trim()||sending||running)return;
    setSending(true);useChat.setState({error:''});stick.current=true;
    try {
      if(!useChat.getState().current)await useChat.getState().fresh();
      const id=useChat.getState().current!.id;
      await chatApi.send({id,text:value,attachments:files,model:model||undefined,retryFrom});
      setText('');setAttachments([]);setEditing(undefined);
    } catch(e){fail(e);} finally {setSending(false);}
  };
  const retry=(index:number)=>{const user=current?.turns.slice(0,index).reverse().find(t=>t.role==='user');if(user)void send(user.text,user.id,user.attachments??[]);};
  const edit=(t:ChatTurn)=>{setText(t.text);setAttachments(t.attachments??[]);setEditing(t.id);input.current?.focus();};
  const transfer=async()=>{
    if(!objective?.trim()||building)return;setBuilding(true);
    try {
      const context=current?.turns.filter(t=>t.status==='complete').map(t=>`${t.role}: ${t.text}${t.attachments?.map(a=>`\nFile ${a.name}:\n${a.text}`).join('')??''}`).join('\n\n')??'';
      const full=`${objective.trim()}\n\nConversation requirements and context:\n${context}`;
      await window.swarm.invoke('runs:resolveTarget', objective.trim());
      const p=await api.projects.create({objective:full});
      const run=await api.runs.start(p.id,full,{});
      await useStore.getState().refreshProjects();await useStore.getState().openProject(p.id,'build');
      await useStore.getState().loadRun(run.id);setObjective(null);
    }catch(e){fail(e);}finally{setBuilding(false);}
  };
  return <div className="chat-workspace">
    <div className="chat-scroll" ref={scroll} onScroll={()=>{const el=scroll.current!;stick.current=el.scrollHeight-el.scrollTop-el.clientHeight<100;}}>
      <div className="chat-messages">{!current?.turns.length ? <div className="chat-empty"><LogoMark size={38}/><h1>What’s on your mind?</h1><p>Ask, explore, write, or work through a problem.</p><div>{['Explain a concept','Help me debug code','Research a topic'].map(s=><button key={s} onClick={()=>{setText(s+' ');input.current?.focus();}}>{s}</button>)}</div></div> : current.turns.map((t,i)=><article data-status={t.status} className={`chat-message ${history.current.ids.has(t.id) ? '' : 'motion-arrive'} ${t.role}`} key={t.id}>
        <div className="chat-message-label">{t.role==='user'?'You':'SWARM'}{t.model&&<span key={t.model} className="motion-arrive">{t.model}</span>}</div>
        <ChatMarkdown text={t.text}/>
        {t.attachments?.map((a,j)=><span className="chat-file" key={j}>{a.name}</span>)}
        {t.status==='streaming'&&<div className="chat-stream" role="status">{t.routing??'Thinking'}<span> ▍</span></div>}
        {t.status==='error'&&<div className="chat-error" role="alert">{t.error}</div>}
        {t.status==='stopped'&&<p className="chat-meta">Response stopped</p>}
        {t.omitted ? <p className="chat-meta">{t.omitted} older messages are outside this model’s context. They remain in this chat.</p> : null}
        <div className="chat-actions"><button onClick={()=>void navigator.clipboard.writeText(t.text).then(()=>setCopied(t.id)).catch(fail)}>{copied===t.id?'Copied':'Copy'}</button>
          {t.role==='user'?<button disabled={running||sending} onClick={()=>edit(t)}>Edit</button>:<><button disabled={running||sending} onClick={()=>retry(i)}>{t.status==='error'?'Retry':'Regenerate'}</button><button disabled={running||sending} onClick={()=>void send('Continue your previous response.',undefined,[])}>Continue</button><span>{t.status==='complete'&&t.routing}</span></>}
        </div>
      </article>)}</div>
    </div>
    <div className="chat-composer-wrap">
      {error&&<div className="chat-error" role="alert">{error}<button aria-label="Dismiss error" onClick={()=>useChat.setState({error:''})}><X size={14}/></button></div>}
      {current?.turns.length ? <button className="chat-build-action" disabled={running} onClick={()=>setObjective(current.turns.filter(t=>t.role==='user').at(-1)?.text??'')}>Build this with SWARM ↗</button> : null}
      {editing&&<div className="chat-meta">Editing message · later replies will be replaced <button onClick={()=>{setEditing(undefined);setText('');}}>Cancel</button></div>}
      <form className={`chat-composer ${sending ? "motion-sent" : ""}`} aria-busy={sending} onSubmit={e=>{e.preventDefault();void send();}}>
        <textarea ref={input} id="chat-input" aria-label="Ask SWARM anything" placeholder="Ask SWARM anything…" value={text} onChange={e=>setText(e.target.value)} onKeyDown={e=>{if(e.key==='Enter'&&!e.shiftKey&&!e.nativeEvent.isComposing){e.preventDefault();void send();}}}/>
        <div className="chat-attachments">{attachments.map((a,i)=><button type="button" key={i} onClick={()=>setAttachments(attachments.filter((_,j)=>i!==j))}>{a.name} ×</button>)}</div>
        <div className="chat-composer-controls"><label className="chat-attach" title="Attach text or code files"><Paperclip size={16}/><input type="file" multiple aria-label="Attach files" onChange={e=>{const files=Array.from(e.target.files??[]);void Promise.all(files.map(async f=>{if(f.size>200_000)throw new Error('Files must be under 200 KB.');const text=await f.text();if(text.includes('\u0000'))throw new Error('Choose text or code files.');return {name:f.name,text};})).then(a=>{if(attachments.length+a.length>5)throw new Error('Attach up to five files.');setAttachments([...attachments,...a]);}).catch(fail);e.target.value='';}}/></label>
          <select aria-label="Chat model" value={model} onChange={e=>setModel(e.target.value)}><option value="">Auto</option>{models.filter(m=>m.enabled).map(m=><option value={m.id} key={m.id}>{m.displayName} · {m.providerId}</option>)}</select>
          {running?<button type="button" className="chat-send" aria-label="Stop generation" onClick={()=>{if(current)void chatApi.stop(current.id).catch(fail);}}><Square size={14}/></button>:<button className="chat-send" aria-label="Send message" disabled={!text.trim()||sending}><ArrowUp size={17}/></button>}
        </div>
      </form><p className="chat-footnote">Chat to explore. Build to execute.</p>
    </div>
    {objective!==null&&<div className="chat-dialog-backdrop"><section className="chat-dialog" role="dialog" aria-modal="true" aria-labelledby="build-chat-title"><h2 id="build-chat-title">Build this with SWARM</h2><p>Describe the outcome and target platform. This conversation will be included as context.</p><textarea aria-label="Build objective" value={objective} onChange={e=>setObjective(e.target.value)} autoFocus/><div><button disabled={building} onClick={()=>setObjective(null)}>Cancel</button><button disabled={building||!objective.trim()} onClick={()=>void transfer()}>{building?'Starting…':'Start autonomous build'}</button></div></section></div>}
  </div>;
}
