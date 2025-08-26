CREATE TABLE textract_index (
    id TEXT PRIMARY KEY,
    doc_id TEXT NOT NULL,
    text TEXT,
    vector VECTOR(1536),
    page INT,
    type TEXT,
    confidence FLOAT,
    metadata JSONB
);