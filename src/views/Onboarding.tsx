import { useState } from 'react';
import { api } from '../lib/api';
import { useStore } from '../lib/store';
import { Button } from '../components/ui';
export function Onboarding(){
  const [error,setError]=useState(''),[busy,setBusy]=useState(false);
  async function start(){setBusy(true);setError('');try{const registry=await api.models.discover();await api.finishOnboarding();useStore.setState({boot:await api.bootstrap(),projects:await api.projects.list(),models:registry.models,providers:registry.providers,view:'home'});}catch(e){setError(e instanceof Error?e.message:'SWARM service unavailable.');}finally{setBusy(false);}}
  return <main className="h-full grid place-items-center"><section className="max-w-lg p-8 space-y-5"><h1 className="text-2xl font-semibold">Welcome to SWARM</h1><p>Your SWARM account includes managed AI access, subject to your plan and service availability. No provider keys or provider accounts are required.</p><Button disabled={busy} onClick={()=>void start()}>{busy?'Loading your workspace…':'Open workspace'}</Button>{error&&<p role="alert">{error}</p>}</section></main>;
}
