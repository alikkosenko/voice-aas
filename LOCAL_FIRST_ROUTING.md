# Local-first routing

Pipeline:

```text
Vosk transcript
      |
      v
CommandParser / LocalCommandPlanner
      | complete direct or chain
      +--------------------------> CommandDispatcher
      |
      | no match / incomplete chain
      v
OpenAiCommandPlanner
      |
      v
CommandDispatcher
```

The technical result saved in AAS settings includes the selected route and the
reason for an AI fallback. This makes it possible to diagnose unexpected API
calls from the vehicle without attaching a debugger.
