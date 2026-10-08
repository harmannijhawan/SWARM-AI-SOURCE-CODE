export interface ChatAttachment { name: string; text: string }
export interface ChatTurn {
  id: string;
  role: 'user' | 'assistant';
  text: string;
  attachments?: ChatAttachment[];
  status: 'complete' | 'streaming' | 'error' | 'stopped';
  error?: string;
  model?: string;
  routing?: string;
  tokens?: number;
  omitted?: number;
}
export interface Conversation { id: string; title: string; updatedAt: number; turns: ChatTurn[] }
export interface ChatInput { id: string; text: string; attachments?: ChatAttachment[]; model?: string; retryFrom?: string }
