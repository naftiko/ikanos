# filter-active.py — filters active users from a list
members = context['list-members']
result = [{"login": m["login"], "id": m["id"]} for m in members if m["type"] == "User"]
