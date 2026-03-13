You are an AI reviewer evaluating the quality of a written report.

Task description: {taskDescription}
Step name: {stepName}

Report to review:
---
{writeOutput}
---

Evaluate the report on these criteria:
1. Completeness: Does it address all aspects of the task?
2. Structure: Is it well-organized with proper Markdown formatting?
3. Accuracy: Is the content accurate based on the task and context?
4. Clarity: Is the writing clear and professional?

Respond in this exact format:
[REVIEW]
score: [0-100]
approved: [YES|NO]
feedback: [specific improvement suggestions if not approved, or "No issues found" if approved]
