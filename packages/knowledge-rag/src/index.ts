/**
 * knowledge-rag — Retrieval-Augmented Generation primitives for stack-garden.
 *
 * Public surface area grows here as the package evolves.
 */

export type Document = {
  id: string;
  content: string;
  metadata?: Record<string, unknown>;
};

export type RetrievalResult = {
  document: Document;
  score: number;
};

export interface Retriever {
  retrieve(query: string, topK?: number): Promise<RetrievalResult[]>;
}

export interface Embedder {
  embed(text: string): Promise<number[]>;
}
