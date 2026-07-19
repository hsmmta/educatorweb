"""Import Qdrant vector data from JSON exports into local Qdrant instance."""
import json
import os
import sys
from qdrant_client import QdrantClient
from qdrant_client.models import Distance, VectorParams, PointStruct

QDRANT_HOST = os.environ.get("QDRANT_HOST", "localhost")
DATA_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)))

client = QdrantClient(host=QDRANT_HOST, port=6333)

files = [
    ("qdrant_kg_references.json", "kg_references"),
    ("qdrant_ml_documents.json", "ml_documents"),
]

for filename, coll_name in files:
    path = os.path.join(DATA_DIR, filename)
    if not os.path.exists(path):
        print(f"SKIP: {filename} not found at {path}")
        continue

    with open(path, "r", encoding="utf-8") as f:
        data = json.load(f)

    points_data = data.get("points", [])
    if not points_data:
        print(f"SKIP: {filename} has no points")
        continue

    # Determine vector size from first point
    first_vector = points_data[0].get("vector", [])
    vec_size = len(first_vector) if isinstance(first_vector, list) and len(first_vector) > 0 else 768

    # Recreate collection
    try:
        client.delete_collection(coll_name)
        print(f"DELETED existing collection: {coll_name}")
    except Exception:
        pass

    client.create_collection(
        collection_name=coll_name,
        vectors_config=VectorParams(size=vec_size, distance=Distance.COSINE),
    )
    print(f"CREATED collection: {coll_name} (dim={vec_size})")

    # Batch upsert
    batch_size = 100
    imported = 0
    for i in range(0, len(points_data), batch_size):
        batch = points_data[i : i + batch_size]
        points = []
        for p in batch:
            pid = p.get("id")
            vector = p.get("vector")
            payload = p.get("payload", {})
            if pid is None or vector is None:
                continue
            if not isinstance(pid, (int, str)):
                pid = str(pid)
            points.append(PointStruct(id=pid, vector=vector, payload=payload))
        if points:
            client.upsert(collection_name=coll_name, points=points)
            imported += len(points)

    print(f"DONE: {coll_name} — {imported} vectors imported")

print("Qdrant import completed successfully.")
