<?php
declare(strict_types=1);
require __DIR__.'/mobile_common.php';
require_method('POST');
$d=body_json();

$username=clean_text($d['username']??'',100);
$password=(string)($d['password']??'');
$deviceId=trim((string)($d['device_id']??''));
$publicKey=(string)($d['public_key_pem']??'');
$deviceName=clean_text($d['device_name']??'Android',190);
$appVersion=clean_text($d['app_version']??'',40);
if($username===''||$password===''||!preg_match('/^[A-Za-z0-9._:-]{16,128}$/',$deviceId)||strlen($publicKey)<100){
  json_out(['error'=>'invalid_request'],400);
}
if(!openssl_pkey_get_public($publicKey))json_out(['error'=>'invalid_public_key'],400);

$pdo=db();
if(!table_exists($pdo,'mobile_users')||!table_exists($pdo,'mobile_devices')||!table_exists($pdo,'mobile_sessions')){
  json_out(['error'=>'schema_upgrade_required'],503);
}
$ip=substr((string)($_SERVER['REMOTE_ADDR']??''),0,64);
$pdo->prepare('DELETE FROM login_attempts WHERE attempted_at < (NOW() - INTERVAL 1 DAY)')->execute();
$st=$pdo->prepare('SELECT COUNT(*) FROM login_attempts WHERE username=? AND ip_address=? AND success=0 AND attempted_at>(NOW()-INTERVAL 15 MINUTE)');$st->execute([$username,$ip]);$pairFails=(int)$st->fetchColumn();
$st=$pdo->prepare('SELECT COUNT(*) FROM login_attempts WHERE username=? AND success=0 AND attempted_at>(NOW()-INTERVAL 15 MINUTE)');$st->execute([$username]);$userFails=(int)$st->fetchColumn();
$st=$pdo->prepare('SELECT COUNT(*) FROM login_attempts WHERE ip_address=? AND success=0 AND attempted_at>(NOW()-INTERVAL 15 MINUTE)');$st->execute([$ip]);$ipFails=(int)$st->fetchColumn();
if($pairFails>=8||$userFails>=14||$ipFails>=40)json_out(['error'=>'too_many_attempts'],429);

$st=$pdo->prepare("SELECT * FROM users WHERE username=? AND kind='inspector' AND active=1 LIMIT 1");
$st->execute([$username]);$u=$st->fetch();
$dummyHash='$2y$12$4J.eGYAqSQvZuXnLqjOMnumN3HGtWSZhzG8ibkQI0Qx/IIpvxW/aK';
$hash=$u?(string)$u['password_hash']:$dummyHash;
$ok=password_verify($password,$hash)&&$u;
$pdo->prepare('INSERT INTO login_attempts(username,ip_address,success,attempted_at) VALUES(?,?,?,NOW())')->execute([$username,$ip,$ok?1:0]);
if(!$ok)json_out(['error'=>'invalid_credentials'],401);
$pdo->prepare('DELETE FROM login_attempts WHERE username=? AND ip_address=? AND success=0')->execute([$username,$ip]);
if(password_needs_rehash((string)$u['password_hash'],PASSWORD_DEFAULT)){
  try{$pdo->prepare('UPDATE users SET password_hash=? WHERE id=?')->execute([password_hash($password,PASSWORD_DEFAULT),$u['id']]);}catch(Throwable $ignore){}
}

$ident=inspector_identity($u);$inspector=$ident['payload']??null;
if(!is_array($inspector)||!empty($inspector['archived'])||!empty($inspector['deletedAt'])||isset($inspector['active'])&&!$inspector['active']){
  json_out(['error'=>'inspector_profile_unavailable'],403);
}
if(isset($inspector['mobileAppEnabled'])&&!$inspector['mobileAppEnabled'])json_out(['error'=>'mobile_access_disabled'],403);
$ref=trim((string)($inspector['id']??$ident['id']??$ident['record_key']??''));
$nationalId=preg_replace('/\D/','',(string)($inspector['nationalId']??$inspector['national_id']??''));
$mobile=trim((string)($inspector['mobile']??''));
$name=trim((string)($inspector['name']??$u['display_name']??$username));
if($ref===''||!preg_match('/^\d{10}$/',$nationalId))json_out(['error'=>'mobile_profile_unavailable'],403);
$parts=preg_split('/\s+/u',$name,-1,PREG_SPLIT_NO_EMPTY)?:[];
$first=(string)(array_shift($parts)?:'بازرس');$last=trim(implode(' ',$parts));if($last==='')$last='سامانه';
$fingerprint=hash('sha256',preg_replace('/\s+/','',$publicKey));
$maxDevices=max(1,min(10,(int)($config['mobile_max_devices_per_user']??3)));

try{
  $pdo->beginTransaction();
  $byRef=$pdo->prepare('SELECT * FROM mobile_users WHERE inspector_ref=? LIMIT 1');$byRef->execute([$ref]);$mr=$byRef->fetch();
  $byNid=$pdo->prepare('SELECT * FROM mobile_users WHERE national_id=? LIMIT 1');$byNid->execute([$nationalId]);$mn=$byNid->fetch();
  if($mr&&$mn&&(int)$mr['id']!==(int)$mn['id']){ $pdo->rollBack(); json_out(['error'=>'mobile_profile_conflict'],409); }
  $mu=$mr?:$mn;
  if($mu){
    $mobileUserId=(int)$mu['id'];
    $pdo->prepare("UPDATE mobile_users SET national_id=?,mobile=?,first_name=?,last_name=?,role='inspector',inspector_ref=?,active=1,updated_at=NOW() WHERE id=?")
      ->execute([$nationalId,$mobile,$first,$last,$ref,$mobileUserId]);
  }else{
    $pdo->prepare("INSERT INTO mobile_users(national_id,mobile,first_name,last_name,role,inspector_ref,active,created_by) VALUES(?,?,?,?, 'inspector',?,1,NULL)")
      ->execute([$nationalId,$mobile,$first,$last,$ref]);
    $mobileUserId=(int)$pdo->lastInsertId();
  }

  $ds=$pdo->prepare('SELECT * FROM mobile_devices WHERE device_id=? LIMIT 1 FOR UPDATE');$ds->execute([$deviceId]);$dev=$ds->fetch();
  if($dev&&(int)$dev['mobile_user_id']!==$mobileUserId){$pdo->rollBack();json_out(['error'=>'device_conflict'],409);}
  if($dev&&$dev['status']==='revoked'){$pdo->rollBack();json_out(['error'=>'device_revoked'],403);}
  if(!$dev){
    $cnt=$pdo->prepare("SELECT COUNT(*) FROM mobile_devices WHERE mobile_user_id=? AND status='approved'");$cnt->execute([$mobileUserId]);
    if((int)$cnt->fetchColumn()>=$maxDevices){$pdo->rollBack();json_out(['error'=>'device_limit_reached'],409);}
  }

  $deviceToken=bin2hex(random_bytes(32));
  if($dev){
    $deviceDbId=(int)$dev['id'];
    $pdo->prepare("UPDATE mobile_devices SET public_key_pem=?,public_key_sha256=?,device_name=?,platform='android',app_version=?,status='approved',device_token_hash=?,approved_at=COALESCE(approved_at,NOW()),last_seen_at=NOW() WHERE id=?")
      ->execute([$publicKey,$fingerprint,$deviceName,$appVersion,token_hash($deviceToken),$deviceDbId]);
  }else{
    $activationRequest=bin2hex(random_bytes(32));
    $pdo->prepare("INSERT INTO mobile_devices(mobile_user_id,device_id,public_key_pem,public_key_sha256,device_name,platform,app_version,status,activation_request_id,device_token_hash,approved_at,last_seen_at) VALUES(?,?,?,?,?,'android',?,'approved',?,?,NOW(),NOW())")
      ->execute([$mobileUserId,$deviceId,$publicKey,$fingerprint,$deviceName,$appVersion,$activationRequest,token_hash($deviceToken)]);
    $deviceDbId=(int)$pdo->lastInsertId();
  }

  $sessionToken=bin2hex(random_bytes(32));
  $pdo->prepare('DELETE FROM mobile_sessions WHERE mobile_device_id=? OR expires_at<NOW()')->execute([$deviceDbId]);
  $pdo->prepare("INSERT INTO mobile_sessions(mobile_device_id,token_hash,created_at,expires_at) VALUES(?,?,NOW(),DATE_ADD(NOW(),INTERVAL 30 MINUTE))")
    ->execute([$deviceDbId,token_hash($sessionToken)]);
  $pdo->commit();

  audit_write(['id'=>$u['id'],'username'=>$u['username'],'role'=>'inspector'],'auth','mobile_password_login','ورود بازرس از اپ اندروید',['ref_type'=>'mobile_device','ref_id'=>(string)$deviceDbId]);
  json_out([
    'ok'=>true,
    'device_token'=>$deviceToken,
    'session_token'=>$sessionToken,
    'expires_in'=>1800,
    'profile'=>['name'=>$name,'role'=>'inspector','inspector_ref'=>$ref,'device_id'=>$deviceId],
    'server_time'=>date('c')
  ]);
}catch(Throwable $e){
  if($pdo->inTransaction())$pdo->rollBack();
  error_log('BAZR mobile_password_login failed: '.$e->getMessage());
  json_out(['error'=>'mobile_login_failed'],500);
}
