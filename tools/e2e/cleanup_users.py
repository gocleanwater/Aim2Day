"""화면 E2E가 만든 시험 계정을 지운다(시험용 DB). 사용: python3 cleanup_users.py email1 email2 ..."""
import sys
import e2elib

e2elib.CREATED_EMAILS.extend(sys.argv[1:])
left = e2elib._cleanup()
print("leftover test users:", left)
sys.exit(1 if left else 0)
